import type { ConsultantStatus } from '../api';

export default function StatusBadge({ status }: { status: ConsultantStatus }) {
  return <span className={`badge ${status.toLowerCase()}`}>{status.toLowerCase()}</span>;
}
