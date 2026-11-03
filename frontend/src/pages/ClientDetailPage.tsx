import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, data, type AssignmentEntry, type ClientDetail } from '../api';
import { clientName } from '../api/names';
import ClientCases from '../components/ClientCases';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './clientdetail.css';

const dateTime = (iso: string) =>
  new Date(iso).toLocaleString('en-ZA', { dateStyle: 'medium', timeStyle: 'short' });

const CHANGE_LABEL: Record<AssignmentEntry['changeType'], string> = {
  IMPORT: 'Imported',
  MANUAL: 'Assigned',
  TRANSFER: 'Transferred',
  BULK_TRANSFER: 'Transferred (batch)',
  UNASSIGN: 'Unassigned',
};

export default function ClientDetailPage() {
  const { id: param = '' } = useParams();
  const id = Number(param);
  const session = useSession();
  const isManager = hasAnyRole(session, ['ADMIN', 'MANAGER']);

  const [result, setResult] = useState<{
    id: number;
    client: ClientDetail | null;
    failed: boolean;
  } | null>(null);
  const [history, setHistory] = useState<{ id: number; rows: AssignmentEntry[] | null } | null>(
    null,
  );

  useEffect(() => {
    if (!Number.isInteger(id)) return;
    let current = true;
    data
      .getClient(id)
      .then((client) => current && setResult({ id, client, failed: false }))
      .catch((err: unknown) => {
        if (current)
          setResult({ id, client: null, failed: !(err instanceof ApiError && err.status === 404) });
      });
    return () => {
      current = false;
    };
  }, [id]);

  // Assignment history is for managers and admins only (consultants would get 403).
  useEffect(() => {
    if (!Number.isInteger(id) || !isManager) return;
    let current = true;
    data
      .listAssignments(id)
      .then((rows) => current && setHistory({ id, rows }))
      .catch(() => current && setHistory({ id, rows: null }));
    return () => {
      current = false;
    };
  }, [id, isManager]);

  const back = (
    <Link className="cd-back" to="/clients">
      &larr; Back to clients
    </Link>
  );

  if (!Number.isInteger(id)) {
    return (
      <div className="cd">
        {back}
        <h1>Client not found</h1>
      </div>
    );
  }

  // Only trust an answer that belongs to the client in the address bar.
  if (!result || result.id !== id) {
    return (
      <div className="cd">
        {back}
        <p className="muted">Loading...</p>
      </div>
    );
  }

  if (result.failed) {
    return (
      <div className="cd">
        {back}
        <p className="alert error" role="alert">
          Could not load this client. Refresh and try again.
        </p>
      </div>
    );
  }

  const c = result.client;
  if (!c) {
    return (
      <div className="cd">
        {back}
        <h1>Client not found</h1>
        <p className="muted">This client does not exist, or you do not have access to them.</p>
      </div>
    );
  }

  const rows = history && history.id === id ? history.rows : undefined; // undefined = still loading

  return (
    <div className="cd">
      {back}
      <h1>{clientName(c)}</h1>
      <p className="muted">
        ID number <span className="mono">{c.idNumberMasked}</span>
      </p>
      <p>
        <Link className="btn" to={`/clients/${c.id}/edit`}>
          Edit client
        </Link>
      </p>

      <div className="cd-grid">
        <section className="cd-card" aria-labelledby="cd-details">
          <h2 id="cd-details">Details</h2>
          <dl>
            <dt>First name</dt>
            <dd>{c.firstName}</dd>
            <dt>Last name</dt>
            <dd>{c.lastName}</dd>
            <dt>Email</dt>
            <dd>{c.email ?? <span className="muted">Not provided</span>}</dd>
            <dt>Phone</dt>
            <dd>{c.phone ?? <span className="muted">Not provided</span>}</dd>
            <dt>Added</dt>
            <dd>{dateTime(c.createdAt)}</dd>
          </dl>
        </section>

        <section className="cd-card" aria-labelledby="cd-work">
          <h2 id="cd-work">Consultant</h2>
          <dl>
            <dt>Current consultant</dt>
            <dd>
              {c.consultant ? (
                <>
                  {c.consultant.fullName}{' '}
                  <span className="muted">({c.consultant.consultantCode})</span>
                </>
              ) : (
                <span className="muted">Unassigned</span>
              )}
            </dd>
            <dt>Last updated</dt>
            <dd>{dateTime(c.updatedAt)}</dd>
          </dl>
        </section>
      </div>

      <ClientCases clientId={c.id} />

      {isManager && (
        <section className="cd-card cd-history" aria-labelledby="cd-hist">
          <h2 id="cd-hist">Assignment history</h2>
          {rows === undefined && <p className="muted">Loading...</p>}
          {rows === null && <p className="muted">Could not load the history.</p>}
          {rows && rows.length === 0 && <p className="muted">No changes recorded yet.</p>}
          {rows && rows.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>When</th>
                    <th>Change</th>
                    <th>From</th>
                    <th>To</th>
                    <th>Reason</th>
                    <th>By</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((h) => (
                    <tr key={`${h.changedAt}-${h.toConsultant?.id ?? 'none'}`}>
                      <td>{dateTime(h.changedAt)}</td>
                      <td>{CHANGE_LABEL[h.changeType]}</td>
                      <td>
                        {h.fromConsultant?.fullName ?? <span className="muted">Unassigned</span>}
                      </td>
                      <td>
                        {h.toConsultant?.fullName ?? <span className="muted">Unassigned</span>}
                      </td>
                      <td>{h.reason}</td>
                      <td>{h.changedBy.email}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
