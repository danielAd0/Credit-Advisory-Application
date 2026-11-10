import { ApiError } from './http';
import { delay } from './mock';
import type { Employer, Product, ProductQuotation } from './admin';
import type { NamedRef, PageResponse } from './types';

const now = () => new Date().toISOString();
const problem = (status: number, code: string, detail: string) =>
  new ApiError(status, detail, { type: `urn:credit-advisory:${code}`, detail });

function page<T>(all: T[], p: number, size: number): PageResponse<T> {
  return {
    items: all.slice(p * size, (p + 1) * size),
    page: p,
    size,
    totalItems: all.length,
    totalPages: Math.max(1, Math.ceil(all.length / size)),
  };
}

const product = (id: number, code: string, name: string, price: number | null): Product => ({
  id,
  code,
  name,
  standardPrice: price,
  countsTowardQuotation: true,
  countsTowardBanked: true,
  active: true,
  displayOrder: id * 10,
  version: 0,
  createdAt: now(),
  updatedAt: now(),
});

const products: Product[] = [
  product(1, 'COMBO', 'Combo', 250),
  product(2, 'IVC', 'IVC', 120),
  product(3, 'INSURANCE', 'Insurance', null),
  product(4, 'MAFORI_CARD', 'Mafori Card', 175),
];

const employer = (id: number, name: string, company: string, type: string): Employer => ({
  id,
  name,
  company,
  employerType: type,
  active: true,
  version: 0,
  createdAt: now(),
  updatedAt: now(),
});

const employers: Employer[] = [
  employer(1, 'Sample Municipality', 'Sample Group', 'Government'),
  employer(2, 'Example Hospital', 'Example Health', 'Health'),
  employer(3, 'Demo Mining Co', 'Demo Holdings', 'Mining'),
];

const quotation = (id: number, name: string): ProductQuotation => ({
  id,
  name,
  active: true,
  version: 0,
  createdAt: now(),
  updatedAt: now(),
});

const productQuotations: ProductQuotation[] = [
  quotation(1, 'Sample Branch North'),
  quotation(2, 'Sample Branch South'),
  quotation(3, 'Sample Branch East'),
];

const namedRef = (x: { id: number; name: string; active: boolean }): NamedRef => ({
  id: x.id,
  name: x.name,
  active: x.active,
});

/**
 * For the mock clients: the employer as a client shows it, or null if there is none.
 * Mirrors the server: 404 for an unknown id, 422 for a newly chosen inactive one.
 */
export function mockEmployerRef(id: number | null, currentId?: number | null): NamedRef | null {
  if (id === null) return null;
  const e = employers.find((x) => x.id === id);
  if (!e) throw problem(404, 'employer:not-found', `Employer ${id} does not exist.`);
  if (!e.active && id !== currentId)
    throw problem(
      422,
      'client:employer-not-active',
      'This employer has been deactivated. Choose another one.',
    );
  return namedRef(e);
}

/** Same as mockEmployerRef, for product quotations. */
export function mockQuotationRef(id: number | null, currentId?: number | null): NamedRef | null {
  if (id === null) return null;
  const q = productQuotations.find((x) => x.id === id);
  if (!q)
    throw problem(404, 'product-quotation:not-found', `Product quotation ${id} does not exist.`);
  if (!q.active && id !== currentId)
    throw problem(
      422,
      'client:product-quotation-not-active',
      'This product quotation has been deactivated. Choose another one.',
    );
  return namedRef(q);
}

export type ProductInput = {
  name: string;
  standardPrice: number | null;
  countsTowardQuotation: boolean;
  countsTowardBanked: boolean;
  displayOrder: number;
};

export async function listProducts(
  params: { search?: string; active?: boolean; page?: number; size?: number } = {},
) {
  await delay();
  const q = (params.search ?? '').trim().toLowerCase();
  const all = products
    .filter((p) => params.active === undefined || p.active === params.active)
    .filter((p) => !q || p.name.toLowerCase().includes(q) || p.code.toLowerCase().includes(q))
    .sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name));
  return page(all, params.page ?? 0, params.size ?? 25);
}

export async function createProduct(input: ProductInput & { code: string }): Promise<Product> {
  await delay();
  const code = input.code
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_');
  if (products.some((p) => p.code === code))
    throw problem(409, 'product:duplicate-code', 'A product with that code already exists.');
  const created: Product = {
    ...input,
    code,
    id: Math.max(0, ...products.map((p) => p.id)) + 1,
    active: true,
    version: 0,
    createdAt: now(),
    updatedAt: now(),
  };
  products.push(created);
  return { ...created };
}

export async function updateProduct(
  id: number,
  input: ProductInput & { version: number },
): Promise<Product> {
  await delay();
  const p = products.find((x) => x.id === id);
  if (!p) throw problem(404, 'product:not-found', 'Product not found.');
  if (p.version !== input.version)
    throw problem(409, 'conflict:stale-version', 'Someone else changed this product first.');
  const { version: _version, ...fields } = input;
  void _version;
  Object.assign(p, fields, { version: p.version + 1, updatedAt: now() });
  return { ...p };
}

export async function setProductActive(
  id: number,
  active: boolean,
  version: number,
): Promise<Product> {
  await delay();
  const p = products.find((x) => x.id === id);
  if (!p) throw problem(404, 'product:not-found', 'Product not found.');
  if (p.version !== version)
    throw problem(409, 'conflict:stale-version', 'Someone else changed this product first.');
  Object.assign(p, { active, version: p.version + 1, updatedAt: now() });
  return { ...p };
}

export type EmployerInput = { name: string; company: string | null; employerType: string | null };

const clean = (s: string) => s.trim().replace(/\s+/g, ' ');

export async function listEmployers(
  params: {
    search?: string;
    active?: boolean;
    employerType?: string;
    page?: number;
    size?: number;
  } = {},
) {
  await delay();
  const q = (params.search ?? '').trim().toLowerCase();
  const all = employers
    .filter((e) => params.active === undefined || e.active === params.active)
    .filter((e) => !params.employerType || e.employerType === params.employerType)
    .filter((e) => !q || e.name.toLowerCase().includes(q))
    .sort((a, b) => a.name.localeCompare(b.name));
  return page(all, params.page ?? 0, params.size ?? 25);
}

export async function createEmployer(input: EmployerInput): Promise<Employer> {
  await delay();
  const name = clean(input.name);
  if (employers.some((e) => e.name.toLowerCase() === name.toLowerCase()))
    throw problem(409, 'employer:duplicate-name', 'An employer with that name already exists.');
  const created: Employer = {
    id: Math.max(0, ...employers.map((e) => e.id)) + 1,
    name,
    company: input.company,
    employerType: input.employerType,
    active: true,
    version: 0,
    createdAt: now(),
    updatedAt: now(),
  };
  employers.push(created);
  return { ...created };
}

export async function updateEmployer(
  id: number,
  input: EmployerInput & { version: number },
): Promise<Employer> {
  await delay();
  const e = employers.find((x) => x.id === id);
  if (!e) throw problem(404, 'employer:not-found', 'Employer not found.');
  if (e.version !== input.version)
    throw problem(409, 'conflict:stale-version', 'Someone else changed this employer first.');
  const name = clean(input.name);
  if (employers.some((x) => x.id !== id && x.name.toLowerCase() === name.toLowerCase()))
    throw problem(409, 'employer:duplicate-name', 'An employer with that name already exists.');
  Object.assign(e, {
    name,
    company: input.company,
    employerType: input.employerType,
    version: e.version + 1,
    updatedAt: now(),
  });
  return { ...e };
}

export async function setEmployerActive(
  id: number,
  active: boolean,
  version: number,
): Promise<Employer> {
  await delay();
  const e = employers.find((x) => x.id === id);
  if (!e) throw problem(404, 'employer:not-found', 'Employer not found.');
  if (e.version !== version)
    throw problem(409, 'conflict:stale-version', 'Someone else changed this employer first.');
  Object.assign(e, { active, version: e.version + 1, updatedAt: now() });
  return { ...e };
}

// ---------- product quotations ----------

export async function listProductQuotations(
  params: { search?: string; active?: boolean; page?: number; size?: number } = {},
) {
  await delay();
  const q = (params.search ?? '').trim().toLowerCase();
  const all = productQuotations
    .filter((x) => params.active === undefined || x.active === params.active)
    .filter((x) => !q || x.name.toLowerCase().includes(q))
    .sort((a, b) => a.name.localeCompare(b.name));
  return page(all, params.page ?? 0, params.size ?? 100);
}

const duplicateQuotation = (name: string, exceptId: number) =>
  productQuotations.some((x) => x.id !== exceptId && x.name.toLowerCase() === name.toLowerCase());

export async function createProductQuotation(input: { name: string }): Promise<ProductQuotation> {
  await delay();
  const name = clean(input.name);
  if (duplicateQuotation(name, 0))
    throw problem(
      409,
      'product-quotation:duplicate-name',
      `A product quotation called ${name} already exists.`,
    );
  const created = quotation(Math.max(0, ...productQuotations.map((x) => x.id)) + 1, name);
  productQuotations.push(created);
  return { ...created };
}

export async function updateProductQuotation(
  id: number,
  input: { name: string; version: number },
): Promise<ProductQuotation> {
  await delay();
  const x = productQuotations.find((k) => k.id === id);
  if (!x) throw problem(404, 'product-quotation:not-found', 'Product quotation not found.');
  if (x.version !== input.version)
    throw problem(
      409,
      'conflict:stale-version',
      'Someone else changed this product quotation first.',
    );
  const name = clean(input.name);
  if (name !== x.name) {
    if (duplicateQuotation(name, id))
      throw problem(
        409,
        'product-quotation:duplicate-name',
        `A product quotation called ${name} already exists.`,
      );
    Object.assign(x, { name, version: x.version + 1, updatedAt: now() });
  }
  return { ...x };
}

export async function setProductQuotationActive(
  id: number,
  active: boolean,
  version: number,
): Promise<ProductQuotation> {
  await delay();
  const x = productQuotations.find((k) => k.id === id);
  if (!x) throw problem(404, 'product-quotation:not-found', 'Product quotation not found.');
  if (x.version !== version)
    throw problem(
      409,
      'conflict:stale-version',
      'Someone else changed this product quotation first.',
    );
  if (x.active !== active) Object.assign(x, { active, version: x.version + 1, updatedAt: now() });
  return { ...x };
}
