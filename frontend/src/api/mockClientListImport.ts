import { saveFile } from './download';
import { ApiError } from './http';
import { delay } from './mock';
import type {
  ClientListAction,
  ClientListConfirmResult,
  ClientListHistoryItem,
  ClientListIssue,
  ClientListRow,
  ClientListSeverity,
  ClientListSummary,
} from './clientListImport';
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

const DIGEST = 'b'.repeat(64);
const FINGERPRINT = 'c'.repeat(64);
const FIRST = ['Lerato', 'Sipho', 'Naledi', 'Thabo', 'Zanele', 'Kagiso', 'Palesa', 'Mpho'];
const LAST = ['Mokoena', 'Dlamini', 'Naidoo', 'Khumalo', 'Botha', 'Sithole', 'Pillay', 'Maseko'];

interface Job {
  summary: ClientListSummary;
  rows: ClientListRow[];
  issues: ClientListIssue[];
}

const jobs = new Map<number, Job>();
let nextId = 1;

function build(fileName: string): Job {
  const wantsConflict = /conflict/i.test(fileName);
  const wantsErrors = /error/i.test(fileName);
  const total = 120;
  const rows: ClientListRow[] = Array.from({ length: total }, (_, i) => {
    const action: ClientListAction =
      i % 11 === 0 ? 'UPDATE' : i % 7 === 0 ? 'UNCHANGED' : i === 5 ? 'DUPLICATE' : 'NEW';
    const invalid = wantsErrors && i % 40 === 3;
    return {
      rowNumber: i + 2,
      action: invalid ? 'INVALID' : action,
      firstName: invalid ? null : FIRST[i % FIRST.length],
      lastName: LAST[(i * 3) % LAST.length],
      idNumber: `*********${String(1000 + ((i * 37) % 9000)).padStart(4, '0')}`,
      consultantName: i % 13 === 0 ? null : i % 2 === 0 ? 'Thandi X' : 'Boitumelo K',
      createsClient: !invalid && action === 'NEW',
      assignsConsultant: !invalid && action === 'NEW' && i % 13 !== 0,
      warningCount: i % 17 === 0 ? 1 : 0,
      outcome: invalid ? 'Rejected' : null,
    };
  });
  const issues: ClientListIssue[] = [
    {
      rowNumber: 7,
      field: 'CELLPHONE',
      code: 'CELLPHONE_FORMAT',
      severity: 'WARNING',
      message: 'The cellphone number was tidied to 0821234567.',
    },
    {
      rowNumber: null,
      field: null,
      code: 'COLUMN_IGNORED',
      severity: 'INFO',
      message: 'The column "Notes" is not used and was ignored.',
    },
    ...(wantsErrors
      ? rows
          .filter((r) => r.action === 'INVALID')
          .map((r): ClientListIssue => ({
            rowNumber: r.rowNumber,
            field: 'FIRST NAME',
            code: 'REQUIRED_MISSING',
            severity: 'ERROR',
            message: 'The first name is missing.',
          }))
      : []),
  ];
  const count = (a: ClientListAction) => rows.filter((r) => r.action === a).length;
  const errors = issues.filter((i) => i.severity === 'ERROR').length;
  const blockers: ClientListSummary['blockers'] = [];
  if (errors > 0) blockers.push('FILE_ERRORS');
  if (wantsConflict) blockers.push('CONSULTANT_CONFLICTS');
  const id = nextId++;
  const now = new Date();
  const summary: ClientListSummary = {
    id,
    state: 'PREVIEWED',
    fileName,
    fileDigest: DIGEST,
    sheetName: 'Clients',
    uploadedBy: 'admin@local.test',
    createdAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + 24 * 3600 * 1000).toISOString(),
    committedAt: null,
    rows: {
      total,
      newClients: count('NEW'),
      update: count('UPDATE'),
      unchanged: count('UNCHANGED'),
      conflict: count('CONFLICT'),
      duplicate: count('DUPLICATE'),
      invalid: count('INVALID'),
    },
    issues: {
      errors,
      warnings: issues.filter((i) => i.severity === 'WARNING').length,
      info: issues.filter((i) => i.severity === 'INFO').length,
    },
    columns: [
      { header: 'FIRST NAME', field: 'firstName' },
      { header: 'SURNAME', field: 'lastName' },
      { header: 'ID NUMBER', field: 'idNumber' },
      { header: 'CELL', field: 'cellphone' },
      { header: 'CONSULTANT', field: 'consultant' },
      { header: 'Notes', field: null },
    ],
    consultants: {
      fingerprint: FINGERPRINT,
      matched: [
        {
          consultantId: 1,
          consultantCode: 'C001',
          fullName: 'Thandi X',
          status: 'ACTIVE',
          namesInSheet: ['Thandi X', 'Thandi'],
          rows: 60,
        },
      ],
      toRegister: [
        { fullName: 'Boitumelo K', email: 'boitumelo@local.test', otherSpellings: [], rows: 52 },
      ],
      emailsToAdd: [
        {
          consultantId: 1,
          consultantCode: 'C001',
          fullName: 'Thandi X',
          email: 'thandi@local.test',
        },
      ],
      conflicts: wantsConflict
        ? [
            {
              nameInSheet: 'Sam Y',
              email: 'thandi@local.test',
              reason: 'This email already belongs to Thandi X, but the name is different.',
              rows: 8,
            },
          ]
        : [],
    },
    additions: {
      debitOrders: 40,
      withCellphone: 110,
      withWorkPhone: 20,
      withHomePhone: 12,
      withEmail: 75,
      withFileNumber: 90,
      withFileId: 85,
      withEmployer: 100,
      withProductQuotation: 95,
      withoutConsultant: 9,
    },
    newEmployers: ['Sunrise Mining', 'Metro Security'],
    newProductQuotations: ['Funeral cover'],
    blockers,
    canConfirm: blockers.length === 0,
    result: null,
  };
  const job = { summary, rows, issues };
  jobs.set(id, job);
  return job;
}

function find(id: number): Job {
  const job = jobs.get(id);
  if (!job) throw problem(404, 'import:not-found', 'Import not found.');
  return job;
}

export async function preview(file: File): Promise<ClientListSummary> {
  await delay(500);
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    throw problem(400, 'file:not-xlsx', 'The file must be an Excel .xlsx workbook.');
  }
  return build(file.name).summary;
}

export async function list(page = 0, size = 10): Promise<PageResponse<ClientListHistoryItem>> {
  await delay();
  const all = [...jobs.values()].reverse().map(({ summary: s }): ClientListHistoryItem => ({
    id: s.id,
    state: s.state,
    fileName: s.fileName,
    sheetName: s.sheetName,
    uploadedBy: s.uploadedBy,
    totalRows: s.rows.total,
    newClients: s.rows.newClients,
    createdAt: s.createdAt,
    committedAt: s.committedAt,
  }));
  return paged(all, page, size);
}

export async function get(id: number): Promise<ClientListSummary> {
  await delay();
  return find(id).summary;
}

export async function rows(
  id: number,
  params: { action?: ClientListAction; page?: number; size?: number } = {},
): Promise<PageResponse<ClientListRow>> {
  await delay();
  const all = find(id).rows.filter((r) => !params.action || r.action === params.action);
  return paged(all, params.page ?? 0, params.size ?? 25);
}

export async function issues(
  id: number,
  params: { severity?: ClientListSeverity; page?: number; size?: number } = {},
): Promise<PageResponse<ClientListIssue>> {
  await delay();
  const all = find(id).issues.filter((i) => !params.severity || i.severity === params.severity);
  return paged(all, params.page ?? 0, params.size ?? 25);
}

export async function exportIssues(id: number, severity?: ClientListSeverity): Promise<void> {
  await delay();
  const all = find(id).issues.filter((i) => !severity || i.severity === severity);
  const csv = [
    'row,field,code,severity,message',
    ...all.map((i) =>
      [i.rowNumber ?? '', i.field ?? '', i.code, i.severity, `"${i.message}"`].join(','),
    ),
  ].join('\n');
  saveFile(
    new Blob(['﻿' + csv], { type: 'text/csv;charset=UTF-8' }),
    `client-list-${id}-issues.csv`,
  );
}

export async function confirm(
  id: number,
  fileDigest: string,
  consultantsFingerprint: string,
): Promise<ClientListConfirmResult> {
  await delay(600);
  const { summary } = find(id);
  if (summary.state !== 'PREVIEWED') {
    throw problem(409, 'import:not-previewed', 'This import was already confirmed or cancelled.');
  }
  if (fileDigest !== summary.fileDigest) {
    throw problem(409, 'import:digest-mismatch', 'The file does not match the one you checked.');
  }
  if (consultantsFingerprint !== summary.consultants.fingerprint) {
    throw problem(
      409,
      'import:consultants-changed',
      'The consultant list changed. Check it again.',
    );
  }
  if (!summary.canConfirm) {
    throw problem(422, 'import:blocking-errors', 'The file has problems that must be fixed first.');
  }
  const result = {
    importJobId: id,
    newClients: summary.rows.newClients,
    updatedClients: summary.rows.update,
    unchangedClients: summary.rows.unchanged,
    assigned: summary.rows.newClients - summary.additions.withoutConsultant,
    newClientsWithoutConsultant: summary.additions.withoutConsultant,
    consultantsRegistered: summary.consultants.toRegister.length,
    consultantEmailsAdded: summary.consultants.emailsToAdd.length,
    consultantAliasesAdded: 1,
    employersCreated: summary.newEmployers.length,
    productQuotationsCreated: summary.newProductQuotations.length,
    staleRows: 0,
    skippedRows: summary.rows.duplicate + summary.rows.invalid,
  };
  summary.state = 'COMMITTED';
  summary.committedAt = new Date().toISOString();
  summary.canConfirm = false;
  summary.blockers = ['FINISHED'];
  summary.result = {
    newClients: result.newClients,
    appliedRows: result.newClients + result.updatedClients,
    staleRows: 0,
    consultantsRegistered: result.consultantsRegistered,
    clientsLinked: result.assigned,
  };
  return result;
}

export async function cancel(id: number): Promise<ClientListSummary> {
  await delay();
  const { summary } = find(id);
  if (summary.state !== 'PREVIEWED') {
    throw problem(409, 'import:not-previewed', 'This import was already confirmed or cancelled.');
  }
  summary.state = 'CANCELLED';
  summary.canConfirm = false;
  summary.blockers = ['FINISHED'];
  return summary;
}
