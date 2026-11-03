import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { data, type DashboardSummary } from '../api';
import { useSession } from '../auth/useAuth';

function Card({
  to,
  label,
  value,
  note,
}: {
  to?: string;
  label: string;
  value: number;
  note?: ReactNode;
}) {
  const body = (
    <>
      <span className="card-label">{label}</span>
      <span className="card-value">{value}</span>
      {note && <span className="card-note">{note}</span>}
    </>
  );
  return to ? (
    <Link to={to} className="card">
      {body}
    </Link>
  ) : (
    <div className="card">{body}</div>
  );
}

export default function Dashboard() {
  const session = useSession();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [failed, setFailed] = useState(false);

  // The counts are worked out by the server, so they stay right however many clients there are.
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
        <div className="cards">
          <Card to="/clients" label={own ? 'My clients' : 'Clients'} value={summary.totalClients} />
          {summary.unassignedClients !== null && (
            <Card
              to="/reassign"
              label="Waiting for a consultant"
              value={summary.unassignedClients}
            />
          )}
          {summary.clientsNeedingHandover !== null && (
            <Card
              to="/reassign"
              label="Need a handover"
              value={summary.clientsNeedingHandover}
              note="With an inactive or suspended consultant"
            />
          )}
          {summary.consultants && (
            <Card
              to="/consultants"
              label="Active consultants"
              value={summary.consultants.active}
              note={`${summary.consultants.inactive} inactive, ${summary.consultants.suspended} suspended`}
            />
          )}
          <Card label="Open cases" value={summary.cases.open} />
          <Card label="Cases in progress" value={summary.cases.inProgress} />
          <Card label="Closed cases" value={summary.cases.closed} />
        </div>
      )}
    </>
  );
}
