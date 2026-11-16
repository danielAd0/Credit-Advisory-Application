import { useEffect, useState } from 'react';
import { detailOf } from '../api';

export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export const rand = (n: number, decimals = 0) =>
  n.toLocaleString('en-ZA', {
    style: 'currency',
    currency: 'ZAR',
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });

export const day = (iso: string) => new Date(iso).toLocaleDateString('en-ZA');

export function periodLabel(from: string, to: string) {
  const a = new Date(from + 'T00:00:00');
  const b = new Date(to + 'T00:00:00');
  const full = (d: Date) =>
    d.toLocaleDateString('en-ZA', { day: 'numeric', month: 'short', year: 'numeric' });
  if (from === to) return full(a);
  if (a.getFullYear() !== b.getFullYear()) return `${full(a)}–${full(b)}`;
  const start =
    a.getMonth() === b.getMonth()
      ? String(a.getDate())
      : a.toLocaleDateString('en-ZA', { day: 'numeric', month: 'short' });
  return `${start}–${full(b)}`;
}

export const monthLabel = (yyyyMm: string) =>
  new Date(yyyyMm + '-01T00:00:00').toLocaleDateString('en-ZA', {
    month: 'long',
    year: 'numeric',
  });

export const compact = (n: number) =>
  n >= 1_000_000
    ? `R${+(n / 1_000_000).toFixed(1)}m`
    : n >= 1000
      ? `R${Math.round(n / 1000)}k`
      : `R${Math.round(n)}`;

export function useReport<T>(load: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<{ value: T | null; error: string | null; key: string }>({
    value: null,
    error: null,
    key: '',
  });
  const key = JSON.stringify(deps);
  useEffect(() => {
    let live = true;
    load()
      .then((value) => live && setState({ value, error: null, key }))
      .catch(
        (err) =>
          live &&
          setState({ value: null, error: detailOf(err) ?? 'Could not load the report.', key }),
      );
    return () => {
      live = false;
    };
    // `load` is a new function on every render; `key` says when the inputs really changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  const current = state.key === key;
  return { value: current ? state.value : null, error: current ? state.error : null };
}
