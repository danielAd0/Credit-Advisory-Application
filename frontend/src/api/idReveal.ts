import { ApiError, api, apiFile } from './http';
import { saveFile } from './download';
import * as mock from './mock';
import type { ClientDetail } from './types';

export const REAUTHENTICATION_REQUIRED = 'urn:credit-advisory:auth:reauthentication-required';

export const ID_REVEAL_LIMIT = 'urn:credit-advisory:client:id-reveal-limit';

export type ExportFormat = 'pdf' | 'csv';

const CONTENT_TYPE: Record<ExportFormat, string> = { pdf: 'application/pdf', csv: 'text/csv' };

const useMock = import.meta.env.VITE_USE_MOCK === 'true';

const real = {
  confirmPassword: (password: string) =>
    api<{ confirmedUntil: string }>('/api/auth/reauthenticate', {
      method: 'POST',
      body: JSON.stringify({ password }),
    }),

  revealIdNumber: async (clientId: number): Promise<string> => {
    const client = await api<ClientDetail & { idNumber: string | null }>(
      `/api/clients/${clientId}?include=idNumber`,
    );
    if (!client.idNumber) throw new ApiError(500, 'The ID number was not returned.');
    return client.idNumber;
  },

  exportClientData: async (clientId: number, format: ExportFormat): Promise<void> => {
    const file = await apiFile(`/api/clients/${clientId}/data-export?format=${format}`, {
      method: 'POST',
    });

    if (!file.type.startsWith(CONTENT_TYPE[format])) {
      const detail = `The server cannot make a ${format.toUpperCase()} file yet. Ask the backend team.`;
      throw new ApiError(501, detail, { type: null, detail });
    }
    saveFile(file, `client-${clientId}-data.${format}`);
  },
};

let mockConfirmedUntil = 0;
let mockReveals = 0;

function gate(askAgain: string) {
  if (Date.now() > mockConfirmedUntil) {
    throw new ApiError(403, askAgain, { type: REAUTHENTICATION_REQUIRED, detail: askAgain });
  }
  if (++mockReveals > 20) {
    const detail =
      'You have viewed the maximum number of full ID numbers for this hour. Try again later.';
    const limit = new ApiError(429, detail, { type: ID_REVEAL_LIMIT, detail });
    limit.retryAfter = 1800;
    throw limit;
  }
}

const cell = (text: string) =>
  `"${(/^[=+\-@]/.test(text) ? `'${text}` : text).replace(/"/g, '""')}"`;

function mockCsv(rows: [string, string, string][]): Blob {
  const lines = ['section,field,value', ...rows.map((r) => r.map(cell).join(','))];
  return new Blob(['\uFEFF' + lines.join('\r\n') + '\r\n'], { type: 'text/csv' });
}

function mockPdf(rows: [string, string, string][]): Blob {
  const plain = (t: string) => t.replace(/[^\x20-\x7e]/g, '?').replace(/[\\()]/g, '\\$&');
  const text = [
    'BT',
    '/F1 12 Tf',
    '16 TL',
    '56 780 Td',
    '(Client data export - demo file) Tj T*',
    ...rows.map(([section, field, value]) => `(${plain(`${section} / ${field}: ${value}`)}) Tj T*`),
    'ET',
  ].join('\n');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${text.length} >>\nstream\n${text}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((body, i) => {
    offsets.push(pdf.length);
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const at of offsets) pdf += `${String(at).padStart(10, '0')} 00000 n \n`;
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return new Blob([pdf], { type: 'application/pdf' });
}

const fake = {
  confirmPassword: async (password: string) => {
    await mock.delay(400);
    if (password === 'wrong') {
      throw new ApiError(400, 'The password is not correct.', {
        type: 'urn:credit-advisory:auth:wrong-password',
        detail: 'The password is not correct.',
      });
    }
    mockConfirmedUntil = Date.now() + 5 * 60 * 1000;
    return { confirmedUntil: new Date(mockConfirmedUntil).toISOString() };
  },
  revealIdNumber: async (clientId: number): Promise<string> => {
    await mock.delay(300);
    gate('Enter your password again to see a full ID number.');
    const client = await mock.getClient(clientId);
    return client.idNumberMasked.replace(/\*/g, '0');
  },
  exportClientData: async (clientId: number, format: ExportFormat): Promise<void> => {
    await mock.delay(500);
    gate("Enter your password again to export this client's data.");
    const client = await mock.getClient(clientId);
    const rows: [string, string, string][] = [
      ['client', 'id', String(client.id)],
      ['client', 'firstName', client.firstName],
      ['client', 'lastName', client.lastName],
      ['client', 'idNumber', client.idNumberMasked.replace(/\*/g, '0')],
      ['export', 'exportedAt', new Date().toISOString()],
      ['export', 'exportedBy', 'mock-admin'],
    ];
    const name = `client-${clientId}-data.${format}`;
    saveFile(format === 'pdf' ? mockPdf(rows) : mockCsv(rows), name);
  },
};

export const idReveal = useMock ? fake : real;
