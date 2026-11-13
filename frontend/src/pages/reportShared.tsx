import { useEffect, useState, type ReactNode } from 'react';
import { detailOf, features } from '../api';

/** Bits shared by the weekly reports and the month / date-range reports. */

export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export const rand = (n: number, decimals = 0) =>
  n.toLocaleString('en-ZA', {
    style: 'currency',
    currency: 'ZAR',
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });

export const day = (iso: string) => new Date(iso).toLocaleDateString('en-ZA');

/** "1–30 Sep 2026", "28 Sep–4 Oct 2026" or "29 Dec 2026–4 Jan 2027". */
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
  }, [key]);
  const current = state.key === key;
  return { value: current ? state.value : null, error: current ? state.error : null };
}

export const Failed = ({ message }: { message: string }) => (
  <p className="alert error" role="alert">
    {message}
  </p>
);

export function Download({ href }: { href: string }) {
  if (!features.reportExport) return null;
  return (
    <a className="btn primary rp-download" href={href}>
      Download as CSV
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
        <path d="M12 4v14M6 12l6 6 6-6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </a>
  );
}

export interface KpiItem {
  label: string;
  value: ReactNode;
  note: ReactNode;
}

export function KpiRow({ items }: { items: KpiItem[] }) {
  return (
    <div className="rp-kpis">
      {items.map((k) => (
        <div key={k.label} className="rp-kpi">
          <span className="rp-kpi-label">{k.label}</span>
          <strong>{k.value}</strong>
          <span className="rp-kpi-note">{k.note}</span>
        </div>
      ))}
    </div>
  );
}
