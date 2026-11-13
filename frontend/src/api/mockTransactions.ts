import { ApiError } from './http';
import { clients, consultants, delay } from './mock';
import type { Product } from './admin';
import { listEmployers } from './mockCatalogue';
import type {
  CaptureLists,
  EditSale,
  NewTransaction,
  SaleDetail,
  SaleFilter,
  SalesPage,
  SavedTransaction,
} from './transactions';
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

const retiredProducts: Product[] = [
  {
    id: 99,
    code: 'FUNERAL_COVER',
    name: 'Funeral Cover',
    standardPrice: null,
    countsTowardQuotation: true,
    countsTowardBanked: true,
    active: false,
    displayOrder: 990,
    version: 0,
    createdAt: stamp,
    updatedAt: stamp,
  },
];

const problem = (status: number, code: string, detail: string) =>
  new ApiError(status, detail, { type: `urn:credit-advisory:${code}`, detail });

export async function lists(): Promise<CaptureLists> {
  await delay();
  const employers = (await listEmployers({ active: true, size: 100 })).items;
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

function priceLines(
  t: {
    transactionDate: string;
    lines: { productId: number; amount: number }[];
  },
  existing: SavedTransaction['lines'] = [],
) {
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
    if (!p) {
      const old = existing.find((x) => x.productId === l.productId);
      if (!old) throw problem(422, 'sale:product-not-active', 'That product is not active.');
      return { ...old, amount: l.amount };
    }
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
  return {
    lines,
    dayOfWeek: weekday === 0 ? 7 : weekday,
    quotationTotal: sum((l) => l.countsTowardQuotation),
    bankedTotal: sum((l) => l.countsTowardBanked),
  };
}

export async function save(t: NewTransaction, key: string): Promise<SavedTransaction> {
  await delay(400);
  const again = saved.get(key);
  if (again) return again;

  if (!clients.some((c) => c.id === t.clientId))
    throw problem(404, 'client:not-found', 'Client not found.');

  const result: SavedTransaction = {
    id: nextId++,
    clientId: t.clientId,
    consultantId: t.consultantId ?? 1,
    transactionDate: t.transactionDate,
    ...priceLines(t),
  };
  saved.set(key, result);
  return result;
}

const dayText = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

const SAMPLE: [string, number][][] = [
  [
    ['INSURANCE', 225],
    ['MAFORI_CARD', 175],
    ['COMBO', 1500],
  ],
  [
    ['INSURANCE', 225],
    ['CREDIT_REPAIR', 850],
  ],
  [
    ['MAFORI_CARD', 175],
    ['HOUSING_ADMIN', 400],
    ['GROCERY', 600],
  ],
  [
    ['INSURANCE', 225],
    ['IVC', 1200],
    ['FUNERAL_COVER', 350],
  ],
  [
    ['CASH_ADVANCE_LITE', 2000],
    ['INSURANCE', 225],
  ],
  [
    ['MAFORI_WELLNESS', 120],
    ['MAFORI_CARD', 175],
  ],
  [
    ['CREDIT_RESCUE', 950],
    ['INSURANCE', 225],
    ['VOLUNTARY_DISTRIBUTION', 300],
  ],
  [
    ['SPEED_SERVICE', 500],
    ['MAFORI_CARD', 175],
  ],
];

let sampleSales: SavedTransaction[] | null = null;

function samples(): SavedTransaction[] {
  if (sampleSales) return sampleSales;
  sampleSales = SAMPLE.flatMap((picks, i) => {
    const date = new Date();
    date.setDate(date.getDate() - i * 2);
    const client = clients[i % clients.length];
    if (!client) return [];
    const lines = picks.flatMap(([code, amount]) => {
      const p = [...products, ...retiredProducts].find((x) => x.code === code);
      return p
        ? [
            {
              productId: p.id,
              name: p.name,
              amount,
              countsTowardQuotation: p.countsTowardQuotation,
              countsTowardBanked: p.countsTowardBanked,
            },
          ]
        : [];
    });
    const sum = (pick: (l: (typeof lines)[number]) => boolean) =>
      lines.filter(pick).reduce((s, l) => s + cents(l.amount), 0) / 100;
    const weekday = date.getDay();
    return [
      {
        id: 900 + i,
        clientId: client.id,
        consultantId: client.consultant?.id ?? 1,
        transactionDate: dayText(date),
        dayOfWeek: weekday === 0 ? 7 : weekday,
        lines,
        quotationTotal: sum((l) => l.countsTowardQuotation),
        bankedTotal: sum((l) => l.countsTowardBanked),
        typedIn: i < 7,
        replaced: i === 6,
      },
    ];
  });
  return sampleSales;
}

export async function list(filter: SaleFilter, page: number, size = 25): Promise<SalesPage> {
  await delay();
  const all = [...samples(), ...saved.values()]
    .filter((t) => t.transactionDate >= filter.from && t.transactionDate <= filter.to)
    .filter((t) => filter.consultantId === undefined || t.consultantId === filter.consultantId)
    .sort((a, b) => b.transactionDate.localeCompare(a.transactionDate) || b.id - a.id);
  const total = (pick: (t: SavedTransaction) => number) =>
    all.reduce((s, t) => s + cents(pick(t)), 0) / 100;
  const items = all.slice(page * size, (page + 1) * size).map((t) => {
    const client = clients.find((c) => c.id === t.clientId);
    return {
      ...t,
      clientName: client ? `${client.firstName} ${client.lastName}` : 'Unknown client',
      consultantName: consultants.find((c) => c.id === t.consultantId)?.fullName ?? 'Unassigned',
    };
  });
  return {
    items,
    page,
    size,
    totalItems: all.length,
    totalPages: Math.ceil(all.length / size),
    quotationTotal: total((t) => t.quotationTotal),
    bankedTotal: total((t) => t.bankedTotal),
  };
}

export async function forClient(clientId: number): Promise<SavedTransaction[]> {
  await delay();
  return [...samples(), ...saved.values()]
    .filter((t) => t.clientId === clientId)
    .sort((a, b) => b.transactionDate.localeCompare(a.transactionDate) || b.id - a.id);
}

const versions = new Map<number, number>();

const isEditable = (t: SavedTransaction) => t.typedIn !== false && t.replaced !== true;

function find(id: number): SavedTransaction {
  const found = [...samples(), ...saved.values()].find((t) => t.id === id);
  if (!found) throw problem(404, 'sale:not-found', 'Sale not found.');
  return found;
}

function toDetail(t: SavedTransaction): SaleDetail {
  return {
    ...t,
    amountToClient: null,
    thirdPartySettlement: null,
    paymentTermId: null,
    employerId: null,
    structureId: null,
    primaryStructureBudgetList: null,
    primaryStructureLoanAmount: null,
    secondaryStructureBudgetList: null,
    secondaryStructureLoanAmount: null,
    typedIn: t.typedIn !== false,
    replaced: t.replaced === true,
    editable: isEditable(t),
    version: versions.get(t.id) ?? 0,
  };
}

function mustBeEditable(t: SavedTransaction, version: number) {
  if (t.typedIn === false)
    throw problem(409, 'sale:imported', 'This sale came from a weekly spreadsheet.');
  if (t.replaced) throw problem(409, 'sale:replaced', "This sale's week has been imported since.");
  if ((versions.get(t.id) ?? 0) !== version)
    throw problem(409, 'conflict:stale-version', 'Someone else saved this sale first. Reload it.');
}

export async function get(id: number): Promise<SaleDetail> {
  await delay();
  return toDetail(find(id));
}

export async function update(id: number, body: EditSale): Promise<SaleDetail> {
  await delay(400);
  const sale = find(id);
  mustBeEditable(sale, body.version);
  Object.assign(sale, {
    transactionDate: body.transactionDate,
    ...priceLines(body, sale.lines),
  });
  versions.set(id, body.version + 1);
  return toDetail(sale);
}

export async function remove(id: number, version: number): Promise<undefined> {
  await delay(400);
  const sale = find(id);
  mustBeEditable(sale, version);
  for (const [key, t] of saved) if (t.id === id) saved.delete(key);
  if (sampleSales) sampleSales = sampleSales.filter((t) => t.id !== id);
  return undefined;
}
