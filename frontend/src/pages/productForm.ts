import type { Product } from '../api';

export interface Form {
  code: string;
  name: string;
  price: string;
  quotation: boolean;
  banked: boolean;
  order: string;
}

export const EMPTY: Form = {
  code: '',
  name: '',
  price: '',
  quotation: true,
  banked: true,
  order: '',
};

export const toForm = (p: Product): Form => ({
  code: p.code,
  name: p.name,
  price: p.standardPrice === null ? '' : String(p.standardPrice),
  quotation: p.countsTowardQuotation,
  banked: p.countsTowardBanked,
  order: String(p.displayOrder),
});

/** Checks the form and turns it into the fields both create and update take, or an error. */
export function productFields(v: Form):
  | {
      fields: {
        name: string;
        standardPrice: number | null;
        countsTowardQuotation: boolean;
        countsTowardBanked: boolean;
        displayOrder: number;
      };
      error?: undefined;
    }
  | { error: string } {
  const price = v.price.trim() === '' ? null : Number(v.price);
  const order = v.order.trim() === '' ? 0 : Number(v.order);
  if (!v.name.trim()) return { error: 'Enter the product name.' };
  if (price !== null && (!Number.isFinite(price) || price < 0))
    return { error: 'The price must be a number, or empty if it is negotiated.' };
  if (!Number.isInteger(order)) return { error: 'The display order must be a whole number.' };
  return {
    fields: {
      name: v.name.trim(),
      standardPrice: price,
      countsTowardQuotation: v.quotation,
      countsTowardBanked: v.banked,
      displayOrder: order,
    },
  };
}
