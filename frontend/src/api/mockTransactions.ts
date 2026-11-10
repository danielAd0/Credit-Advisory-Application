import { ApiError } from './http';
import { clients, delay } from './mock';
import type { Employer, Product } from './admin';
import type { CaptureLists, NewTransaction, SavedTransaction } from './transactions';
import type { ClientSummary } from './types';

const stamp = '2026-01-01T00:00:00Z';

const seed: [string, string, number | null, boolean][] = [
  ['COMBO', 'Combo', null, true],
  ['IVC', 'IVC', null, true],
  ['INSURANCE', 'Insurance', 225, true],
  ['MAFORI_CARD', 'Mafori Card', 175, true],
  ['MAFORI_VOUCHER', 'Mafori Voucher', null, true],
  ['CREDIT_RESCUE', 'Credit Rescue', null, true],
  ['CREDIT_REPAIR', 'Credit Repair', null, false],
  ['CREDIT_REPAIR_DEBIT_ORDER', 'Credit Repair Debit Order', null, false],
  ['SPEED_SERVICE', 'Speed Service', null, true],
  ['CASH_ADVANCE_LITE', 'Cash Advance Lite', null, false],
  ['GROCERY', 'Grocery', null, false],
  ['HOUSING_ADMIN', 'Housing Admin', null, true],
  ['MAFORI_WELLNESS', 'Mafori Wellness', null, true],
  ['VOLUNTARY_DISTRIBUTION', 'Voluntary Distribution', null, true],
  ['POLICY_REPLACEMENT', 'Policy Replacement', null, false],
];

const products: Product[] = seed.map(([code, name, price, banked], i) => ({
  id: i + 1,
  code,
  name,
  standardPrice: price,
  countsTowardQuotation: true,
  countsTowardBanked: banked,
  active: true,
  displayOrder: (i + 1) * 10,
  version: 0,
  createdAt: stamp,
  updatedAt: stamp,
}));

const employers: Employer[] = ['Phumelela Local', 'Matzikama Local', 'Sample Municipality'].map(
  (name, i) => ({
    id: i + 1,
    name,
    company: null,
    employerType: null,
    active: true,
    version: 0,
    createdAt: stamp,
    updatedAt: stamp,
  }),
);

const problem = (status: number, code: string, detail: string) =>
  new ApiError(status, detail, { type: `urn:credit-advisory:${code}`, detail });

export async function lists(): Promise<CaptureLists> {
  await delay();
  return {
    products,
    employers,
    paymentTerms: [
      { id: 1, name: 'Once off payment' },
      { id: 2, name: 'Within 12 months' },
    ],
    structures: [
      { id: 1, name: 'VITAL CREDIT' },
      { id: 2, name: 'MAFORI CREDIT' },
    ],
  };
}

export async function lookupClient(idNumber: string): Promise<ClientSummary> {
  await delay();
  const found = clients.find((c) => c.idNumberMasked.endsWith(idNumber.slice(-4)));
  if (!found) throw problem(404, 'client:not-found', 'No client found with this ID number.');
  return found;
}

const saved = new Map<string, SavedTransaction>();

let nextId = 1000;

const cents = (n: number) => Math.round(n * 100);

export async function save(t: NewTransaction, key: string): Promise<SavedTransaction> {
  await delay(400);
  const again = saved.get(key);
  if (again) return again;

  if (!clients.some((c) => c.id === t.clientId))
    throw problem(404, 'client:not-found', 'Client not found.');
  if (t.lines.length === 0)
    throw problem(400, 'validation', 'Add at least one product with an amount.');
  if (t.transactionDate > new Date().toISOString().slice(0, 10))
    throw problem(400, 'validation', 'The date cannot be in the future.');
  for (const l of t.lines) {
    if (!(l.amount > 0) || cents(l.amount) / 100 !== l.amount)
      throw problem(400, 'validation', 'Every amount must be above 0 with at most two decimals.');
  }

  const lines = t.lines.map((l) => {
    const p = products.find((x) => x.id === l.productId);
    if (!p) throw problem(400, 'validation', 'Unknown or inactive product.');
    return {
      productId: p.id,
      name: p.name,
      amount: l.amount,
      countsTowardQuotation: p.countsTowardQuotation,
      countsTowardBanked: p.countsTowardBanked,
    };
  });
  const sum = (pick: (l: (typeof lines)[number]) => boolean) =>
    lines.filter(pick).reduce((s, l) => s + cents(l.amount), 0) / 100;

  const weekday = new Date(`${t.transactionDate}T00:00:00`).getDay();
  const result: SavedTransaction = {
    id: nextId++,
    clientId: t.clientId,
    consultantId: t.consultantId ?? 1,
    transactionDate: t.transactionDate,
    dayOfWeek: weekday === 0 ? 7 : weekday,
    lines,
    quotationTotal: sum((l) => l.countsTowardQuotation),
    bankedTotal: sum((l) => l.countsTowardBanked),
  };
  saved.set(key, result);
  return result;
}
