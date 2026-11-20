import { api } from './http';
import { delay } from './mock';
import type { PageResponse } from './types';

export type WorkbookCheckStatus = 'NOT_CHECKED' | 'MATCHED' | 'DIFFERENCES';

export interface WorkbookCheckSection {
  sheetName: string;
  section: string;
  figures: number;
  compared: number;
  differences: number;
}

export interface WorkbookCheckDefect {
  defectCode: string;
  figures: number;
  differences: number;
  description: string;
}

export interface WorkbookCheckSummary {
  importJobId: number;
  status: WorkbookCheckStatus;
  figures: number;
  compared: number;
  differences: number;
  sections: WorkbookCheckSection[];
  defects: WorkbookCheckDefect[];
}

export interface WorkbookCheckFigure {
  id: number;
  sheetName: string;
  section: string;
  blockTitle: string | null;
  rowLabel: string | null;
  columnLabel: string | null;
  cellRef: string;
  sheetValue: number | null;
  sheetText: string | null;
  sheetError: string | null;
  formula: string | null;
  computedValue: number | null;
  computedText: string | null;
  difference: number | null;
  matches: boolean | null;
  defectCode: string | null;
  defectDescription: string | null;
}

export type WorkbookCheckFilter = {
  sheet?: string;
  section?: string;
  defect?: string;
  onlyDifferences?: boolean;
  page?: number;
  size?: number;
};

function query(params: Record<string, string | number | boolean | undefined>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') q.set(key, String(value));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

const real = {
  summary: (id: number) => api<WorkbookCheckSummary>(`/api/imports/${id}/reconciliation`),

  figures: (id: number, params: WorkbookCheckFilter = {}) =>
    api<PageResponse<WorkbookCheckFigure>>(`/api/imports/${id}/report-figures${query(params)}`),
};

const MOCK_DEFECTS: WorkbookCheckDefect[] = [
  {
    defectCode: 'WRONG_SHEET',
    figures: 14,
    differences: 6,
    description: 'The formula reads another day’s sheet.',
  },
  {
    defectCode: 'ROW_OFFSET',
    figures: 22,
    differences: 9,
    description:
      'The formula uses another row’s label, so it shows another consultant’s (or structure’s) figure.',
  },
  {
    defectCode: 'THRESHOLD_GREATER_THAN',
    figures: 7,
    differences: 2,
    description: 'The target test uses ">", so exactly reaching the target shows "Not Reached".',
  },
];

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

const MOCK_FIGURES: WorkbookCheckFigure[] = Array.from({ length: 40 }, (_, n) => {
  const defect = MOCK_DEFECTS[n % MOCK_DEFECTS.length];
  const computed = 1000 + n * 137.5;
  const wrong = n % 3 !== 2;
  const shown = wrong ? computed - (n % 2 === 0 ? 250 : 80.5) : computed;
  return {
    id: n + 1,
    sheetName: n % 5 === 4 ? 'Breakdown' : 'Report',
    section: n % 2 === 0 ? 'DAILY_BANKED' : 'DAILY_QUOTATION',
    blockTitle: n % 2 === 0 ? 'BANKED' : 'QUOTATION',
    rowLabel: DAYS[n % 7],
    columnLabel: n % 2 === 0 ? 'BANKED' : 'QUOTATION',
    cellRef: `C${9 + n}`,
    sheetValue: shown,
    sheetText: null,
    sheetError: null,
    formula: `=SUM(${DAYS[(n + 1) % 7]}!T:T)`,
    computedValue: computed,
    computedText: null,
    difference: shown - computed,
    matches: !wrong,
    defectCode: defect.defectCode,
    defectDescription: defect.description,
  };
});

const mock = {
  async summary(id: number): Promise<WorkbookCheckSummary> {
    await delay(300);
    const differences = MOCK_FIGURES.filter((f) => f.matches === false).length;
    return {
      importJobId: id,
      status: 'DIFFERENCES',
      figures: MOCK_FIGURES.length,
      compared: MOCK_FIGURES.length,
      differences,
      sections: [
        {
          sheetName: 'Report',
          section: 'DAILY_BANKED',
          figures: 20,
          compared: 20,
          differences: 8,
        },
        {
          sheetName: 'Report',
          section: 'DAILY_QUOTATION',
          figures: 16,
          compared: 16,
          differences: 5,
        },
        {
          sheetName: 'Breakdown',
          section: 'BREAKDOWN',
          figures: 4,
          compared: 4,
          differences: 1,
        },
      ],
      defects: MOCK_DEFECTS,
    };
  },

  async figures(
    _id: number,
    params: WorkbookCheckFilter = {},
  ): Promise<PageResponse<WorkbookCheckFigure>> {
    await delay(300);
    const all = MOCK_FIGURES.filter(
      (f) =>
        (!params.sheet || f.sheetName === params.sheet) &&
        (!params.section || f.section === params.section) &&
        (!params.defect || f.defectCode === params.defect) &&
        (!params.onlyDifferences || f.matches === false),
    );
    const page = params.page ?? 0;
    const size = params.size ?? 25;
    return {
      items: all.slice(page * size, (page + 1) * size),
      page,
      size,
      totalItems: all.length,
      totalPages: Math.max(1, Math.ceil(all.length / size)),
    };
  },
};

export const workbookCheck = import.meta.env.VITE_USE_MOCK === 'true' ? mock : real;
