import type { ReactNode } from 'react';
import { features } from '../api';

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
