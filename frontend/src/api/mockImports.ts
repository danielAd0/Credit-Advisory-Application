import { saveFile } from './download';
import { ApiError } from './http';
import { consultants, delay } from './mock';
import type {
  ConfirmResult,
  ImportHistoryItem,
  ImportIssue,
  ImportRow,
  ImportSummary,
  RowAction,
  Severity,
} from './imports';
import type { PageResponse } from './types';

const problem = (status: number, code: string, detail: string) =>
  new ApiError(status, detail, { type: `urn:credit-advisory:${code}`, detail });

function paged<T>(all: T[], page: number, size: number): PageResponse<T> {
  return {
    items: all.slice(page * size, (page + 1) * size),
    page,
    size,
    totalItems: all.length,
    totalPages: Math.max(1, Math.ceil(all.length / size)),
  };
}

const iso = (d: Date) => d.toISOString().slice(0, 10);
const DIGEST = 'a'.repeat(64);

function monday(d: Date): Date {
  const x = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  x.setUTCDate(x.getUTCDate() - ((x.getUTCDay() + 6) % 7));
  return x;
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

function guess(fileName: string): string | null {
  const iso8601 = /(\d{4})-(\d{2})-(\d{2})/.exec(fileName);
  if (iso8601) return iso(monday(new Date(+iso8601[1], +iso8601[2] - 1, +iso8601[3])));
  const m = /(\d{1,2})(?:\s*-\s*\d{1,2})?\s+([A-Za-z]{3,})\s+(\d{4})/.exec(fileName);
  if (!m) return null;
  const month = MONTHS.indexOf(m[2].slice(0, 3).toLowerCase());
  if (month === -1) return null;
  return iso(monday(new Date(+m[3], month, +m[1])));
}

const NAMES_IN_SHEET = ['Thandi X', 'Boitumelo K'];
const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

interface Job {
  summary: ImportSummary;
  rows: ImportRow[];
  issues: ImportIssue[];
  mapped: Map<string, number>;
}

const jobs = new Map<number, Job>();
const committedWeeks = new Map<string, number>();
let nextId = 1;

function buildRows(week: string): ImportRow[] {
  const actions: RowAction[] = [
    'NEW',
    'NEW',
    'UNCHANGED',
    'NEW',
    'DUPLICATE',
    'INVALID',
    'NEW',
    'UNCHANGED',
  ];
  const start = new Date(week + 'T00:00:00Z');
  return actions.map((action, i) => {
    const day = new Date(start);
    day.setUTCDate(start.getUTCDate() + (i % 5));
    return {
      id: 500 + i,
      sheetName: DAYS[i % 5],
      rowNumber: 5 + i,
      date: iso(day),
      action,
      outcome: null,
      idNumberMasked: `*********${String(80 + i).padStart(4, '0')}`,
      firstName: 'Sample',
      lastName: `Client ${String(i + 1).padStart(2, '0')}`,
      consultantName: NAMES_IN_SHEET[i % 2],
      employerName: 'SAMPLE LOCAL MUNICIPALITY',
      quotation: action === 'INVALID' ? 0 : 2400,
      banked: action === 'INVALID' ? 0 : 2175,
      products: (action === 'INVALID' ? {} : { INSURANCE: 225, MAFORI_CARD: 175 }) as Record<
        string,
        number
      >,
      warnings: i === 2 ? 1 : 0,
    };
  });
}

const ISSUES: ImportIssue[] = [
  {
    sheetName: 'Wednesday',
    rowNumber: 10,
    field: 'ID NUMBER',
    code: 'row:invalid-id-number',
    severity: 'ERROR',
    message: 'Not a valid SA ID number: wrong check digit.',
  },
  {
    sheetName: 'Tuesday',
    rowNumber: 7,
    field: 'CONSULTANT',
    code: 'row:repeat-client',
    severity: 'WARNING',
    message: 'This client appears more than once in the file.',
  },
  {
    sheetName: 'Monday',
    rowNumber: 5,
    field: 'EMPLOYER',
    code: 'lookup:new-employer',
    severity: 'INFO',
    message: 'New employer will be created.',
  },
];

function refresh(job: Job) {
  const s = job.summary;
  s.unmappedConsultants = NAMES_IN_SHEET.filter((n) => !job.mapped.has(n)).map((n) => ({
    nameInSheet: n,
    rows: 4,
  }));
  const blockers: ImportSummary['blockers'] = [];
  if (s.state === 'PREVIEWED' && s.unmappedConsultants.length > 0)
    blockers.push('UNMAPPED_CONSULTANTS');
  if (s.state === 'EXPIRED') blockers.push('EXPIRED');
  if (s.state === 'COMMITTED' || s.state === 'CANCELLED') blockers.push('NOT_PREVIEWED');
  s.blockers = blockers;
  s.canConfirm = blockers.length === 0;
}

export async function weekGuess(fileName: string): Promise<string | null> {
  await delay(200);
  return guess(fileName);
}

export async function preview(file: File, weekStarting?: string): Promise<ImportSummary> {
  await delay(900);
  if (!/\.xlsx$/i.test(file.name))
    throw problem(400, 'file:not-xlsx', 'The file must be an Excel .xlsx workbook.');
  const week = weekStarting || guess(file.name);
  if (!week) throw problem(400, 'import:week-required', 'Choose which week this register is for.');
  if (new Date(week + 'T00:00:00Z').getUTCDay() !== 1)
    throw problem(400, 'import:week-not-monday', 'The week must start on a Monday.');
  const start = new Date(week + 'T00:00:00Z');
  const end = new Date(start);
  end.setUTCDate(start.getUTCDate() + 6);
  const id = nextId++;
  const rows = buildRows(week);
  const heldBy = committedWeeks.get(week) ?? null;
  const summary: ImportSummary = {
    id,
    fileName: file.name,
    fileDigest: DIGEST,
    state: 'PREVIEWED',
    weekStarting: week,
    weekEnding: iso(end),
    uploadedBy: sessionStorage.getItem('mockUser') ?? 'unknown',
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 24 * 3600_000).toISOString(),
    committedAt: null,
    totalRows: rows.length,
    newClients: rows.filter((r) => r.action === 'NEW').length,
    existingClients: rows.filter((r) => r.action === 'UNCHANGED').length,
    repeatRows: rows.filter((r) => r.action === 'DUPLICATE').length,
    rejectedRows: rows.filter((r) => r.action === 'INVALID').length,
    appliedRows: 0,
    errors: 1,
    warnings: 1,
    infos: 1,
    quotationTotal: 16800,
    bankedTotal: 15225,
    days: DAYS.map((name, i) => {
      const date = new Date(start);
      date.setUTCDate(start.getUTCDate() + i);
      const has = i < 5;
      return {
        sheetName: has ? name : null,
        dayOfWeek: i + 1,
        date: iso(date),
        rows: has ? rows.filter((r) => r.sheetName === name).length : 0,
        rejected: has
          ? rows.filter((r) => r.sheetName === name && r.action === 'INVALID').length
          : 0,
        quotation: has
          ? rows.filter((r) => r.sheetName === name).reduce((a, r) => a + r.quotation, 0)
          : 0,
        banked: has
          ? rows.filter((r) => r.sheetName === name).reduce((a, r) => a + r.banked, 0)
          : 0,
      };
    }),
    unmappedConsultants: [],
    weekImportedByJobId: heldBy,
    replaceRequired: heldBy !== null,
    canConfirm: false,
    blockers: [],
    typedInSales: heldBy === null ? 3 : 0,
  };
  const job: Job = { summary, rows, issues: ISSUES, mapped: new Map() };
  refresh(job);
  jobs.set(id, job);
  return { ...summary };
}

const find = (id: number) => {
  const job = jobs.get(id);
  if (!job) throw problem(404, 'import:not-found', 'Import not found.');
  return job;
};

export async function list(page = 0, size = 20): Promise<PageResponse<ImportHistoryItem>> {
  await delay();
  const all = [...jobs.values()].reverse().map(({ summary: s }) => ({
    id: s.id,
    fileName: s.fileName,
    state: s.state,
    weekStarting: s.weekStarting,
    uploadedBy: s.uploadedBy,
    createdAt: s.createdAt,
    committedAt: s.committedAt,
    totalRows: s.totalRows,
    rejectedRows: s.rejectedRows,
    appliedRows: s.appliedRows,
    replacesImportJobId: null,
  }));
  return paged(all, page, size);
}

export async function get(id: number): Promise<ImportSummary> {
  await delay(300);
  return { ...find(id).summary };
}

export async function rows(
  id: number,
  params: { sheet?: string; action?: RowAction; page?: number; size?: number } = {},
) {
  await delay(300);
  const all = find(id).rows.filter(
    (r) =>
      (!params.sheet || r.sheetName === params.sheet) &&
      (!params.action || r.action === params.action),
  );
  return paged(all, params.page ?? 0, params.size ?? 50);
}

export async function issues(
  id: number,
  params: { severity?: Severity; page?: number; size?: number } = {},
) {
  await delay(300);
  const all = find(id).issues.filter((i) => !params.severity || i.severity === params.severity);
  return paged(all, params.page ?? 0, params.size ?? 50);
}

export async function exportIssues(id: number, severity?: Severity): Promise<void> {
  await delay(300);
  const cell = (value: string | number | null) => {
    const text = value === null ? '' : String(value);
    const safe = /^[=+\-@]/.test(text) ? `'${text}` : text;
    return `"${safe.replace(/"/g, '""')}"`;
  };
  const header = ['Sheet', 'Row', 'Field', 'Severity', 'Code', 'Message', 'ID number (last 4)'];
  const lines = find(id)
    .issues.filter((i) => !severity || i.severity === severity)
    .map((i) =>
      [i.sheetName, i.rowNumber, i.field, i.severity, i.code, i.message, ''].map(cell).join(','),
    );
  const csv = [header.map(cell).join(','), ...lines].join('\r\n');
  saveFile(
    new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=UTF-8' }),
    `import-${id}-issues.csv`,
  );
}

export async function mapConsultant(
  id: number,
  nameInSheet: string,
  consultantId: number,
): Promise<ImportSummary> {
  await delay(400);
  const job = find(id);
  if (!consultants.some((c) => c.id === consultantId))
    throw problem(404, 'consultant:not-found', 'Consultant not found.');
  if (!NAMES_IN_SHEET.includes(nameInSheet))
    throw problem(422, 'import:name-not-in-import', 'No row in this file has that name.');
  if (job.mapped.has(nameInSheet))
    throw problem(409, 'import:name-already-mapped', 'That name is already matched.');
  job.mapped.set(nameInSheet, consultantId);
  refresh(job);
  return { ...job.summary };
}

export async function confirm(
  id: number,
  fileDigest: string,
  replaceReason: string | null,
): Promise<ConfirmResult> {
  await delay(1200);
  const job = find(id);
  const s = job.summary;
  if (s.state !== 'PREVIEWED')
    throw problem(409, 'import:not-previewed', 'This import was already confirmed or cancelled.');
  if (fileDigest !== s.fileDigest)
    throw problem(409, 'import:digest-mismatch', 'That is not the file you previewed.');
  if (s.unmappedConsultants.length > 0)
    throw problem(422, 'import:unmapped-consultants', 'Match every consultant name first.');
  if (s.replaceRequired && !replaceReason?.trim())
    throw problem(422, 'import:replace-reason-required', 'Give a reason for replacing this week.');
  s.state = 'COMMITTED';
  s.committedAt = new Date().toISOString();
  s.appliedRows = s.totalRows - s.rejectedRows;
  committedWeeks.set(s.weekStarting, s.id);
  refresh(job);
  return {
    importJobId: s.id,
    reportingWeekId: s.id,
    weekStarting: s.weekStarting,
    transactions: s.appliedRows,
    productLines: s.appliedRows * 2,
    extraValues: 0,
    newClients: s.newClients,
    clientsAssigned: s.newClients,
    employersCreated: 1,
    structuresCreated: 0,
    paymentTermsCreated: 0,
    staleRows: 0,
    replacedImportJobId: s.weekImportedByJobId,
    supersededTransactions: 0,
    replacedTypedInSales: s.typedInSales,
  };
}

export async function cancel(id: number): Promise<ImportSummary> {
  await delay(400);
  const job = find(id);
  if (job.summary.state !== 'PREVIEWED')
    throw problem(409, 'import:not-previewed', 'This import was already confirmed or cancelled.');
  job.summary.state = 'CANCELLED';
  refresh(job);
  return { ...job.summary };
}
