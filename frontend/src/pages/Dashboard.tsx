import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { data, type DashboardSummary } from '../api';
import './dashboard.css';
import { hasAnyRole, useSession } from '../auth/useAuth';
import DashboardCharts from '../components/DashboardCharts';
import WeekSnapshot from '../components/WeekSnapshot';

interface Row {
  to?: string;
  label: string;
  value: number;
  note?: string;
}


function StatusTable({ rows }: { rows: Row[] }) {
  return (
    <section className="st" aria-label="Status summary">
      <h2>Status summary</h2>
      <table>
        <colgroup>
          <col className="c-metric" />
          <col className="c-count" />
          <col />
        </colgroup>
        <thead>
          <tr>
            <th>Metric</th>
            <th className="num">Count</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label}>
              <td>{r.to ? <Link to={r.to}>{r.label}</Link> : r.label}</td>
              <td className="num">
                <strong>{r.value}</strong>
              </td>
              <td className="muted">{r.note}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

export default function Dashboard() {
  const session = useSession();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [failed, setFailed] = useState(false);



  useEffect(() => {
    let live = true;
    data
      .getDashboardSummary()
      .then((s) => live && setSummary(s))
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
    };
  }, []);

  const own = summary?.scope === 'OWN';
  const rows: Row[] = summary
    ? [
        { to: '/clients', label: own ? 'My clients' : 'Clients', value: summary.totalClients },
        ...(summary.unassignedClients !== null
          ? [
              {
                to: '/reassign',
                label: 'Waiting for a consultant',
                value: summary.unassignedClients,
                note: summary.unassignedClients > 0 ? 'Action needed' : 'None waiting',
              },
            ]
          : []),
        ...(summary.clientsNeedingHandover !== null
          ? [
              {
                to: '/reassign',
                label: 'Need a handover',
                value: summary.clientsNeedingHandover,
                note: 'With an inactive or suspended consultant',
              },
            ]
          : []),
        ...(summary.consultants
          ? [
              {
                to: '/consultants',
                label: 'Active consultants',
                value: summary.consultants.active,
                note: `${summary.consultants.inactive} inactive, ${summary.consultants.suspended} suspended`,
              },
            ]
          : []),
        { label: 'Open cases', value: summary.cases.open },
        { label: 'Cases in progress', value: summary.cases.inProgress },
        { label: 'Closed cases', value: summary.cases.closed },
      ]
    : [];
  const manager = hasAnyRole(session, ['ADMIN', 'MANAGER']);

  return (
    <>
      <h1>Dashboard</h1>
      <p className="muted">Signed in as {session.email}.</p>

      {failed && (
        <p className="alert error" role="alert">
          Could not load the dashboard. Refresh and try again.
        </p>
      )}
      {!failed && summary === null && <p className="muted">Loading...</p>}

      {summary && (
        <div className="dash-top">
          <WeekSnapshot />
          <StatusTable rows={rows} />
        </div>
      )}

      {summary && manager && <DashboardCharts />}
    </>
  );
}
