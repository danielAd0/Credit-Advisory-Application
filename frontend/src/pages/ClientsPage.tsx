import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, data, features, type ClientSummary } from '../api';
import { clientName } from '../api/names';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './clientsactions.css';

export default function ClientsPage() {
  const session = useSession();
  const isManager = hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const [query, setQuery] = useState('');
  const [version, setVersion] = useState(0); // bumped after a removal so the list reloads
  const [result, setResult] = useState<{
    query: string;
    version: number;
    rows: ClientSummary[];
  } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [target, setTarget] = useState<ClientSummary | null>(null);
  const [reason, setReason] = useState('');
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);

  // Wait 250 ms after typing stops, and ignore answers that arrive late.
  useEffect(() => {
    let current = true;
    const t = setTimeout(() => {
      data
        .listClients(query)
        .then((r) => {
          if (current) {
            setResult({ query, version, rows: r });
            setError(null);
          }
        })
        .catch(() => {
          if (current) setError('Could not load clients.');
        });
    }, 250);
    return () => {
      current = false;
      clearTimeout(t);
    };
  }, [query, version]);

  // Open and close the native dialog to match what is selected for removal.
  useEffect(() => {
    const d = dialogRef.current;
    if (!d) return;
    if (target && !d.open) d.showModal();
    if (!target && d.open) d.close();
  }, [target]);

  const rows = result && result.query === query && result.version === version ? result.rows : null;

  function openRemove(c: ClientSummary) {
    setTarget(c);
    setReason('');
    setRemoveError(null);
  }

  async function confirmRemove() {
    if (!target || reason.trim().length < 3) return;
    setRemoving(true);
    setRemoveError(null);
    try {
      await data.removeClient(target.id, reason.trim());
      setNotice(`${clientName(target)} was removed.`);
      setTarget(null);
      setVersion((v) => v + 1);
    } catch (err) {
      setRemoveError(
        err instanceof ApiError && err.status === 403
          ? 'You do not have permission to remove clients.'
          : err instanceof ApiError && err.status === 409
            ? 'This client has open cases and cannot be removed yet.'
            : 'Could not remove the client. Nothing was changed.',
      );
    } finally {
      setRemoving(false);
    }
  }

  return (
    <>
      <h1>Clients</h1>
      <div className="toolbar toolbar-actions">
        <input
          type="search"
          placeholder="Search by name"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search clients"
          autoComplete="off"
        />
        <Link className="btn primary" to="/clients/new">
          Add client
        </Link>
        {isManager && features.import && (
          <Link className="btn" to="/import">
            Import from Excel
          </Link>
        )}
      </div>

      {notice && (
        <p className="alert ok" role="status">
          {notice}
        </p>
      )}
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && rows === null && <p className="muted">Loading...</p>}
      {rows && rows.length === 0 && <p className="muted">No clients match your search.</p>}

      {rows && rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>ID number</th>
                <th>Consultant</th>
                <th>Updated</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td>
                    <Link className="row-link" to={`/clients/${c.id}`}>
                      {clientName(c)}
                    </Link>
                  </td>
                  <td className="mono">{c.idNumberMasked}</td>
                  <td>{c.consultant?.fullName ?? <span className="muted">Unassigned</span>}</td>
                  <td>{new Date(c.updatedAt).toLocaleDateString('en-ZA')}</td>
                  <td className="ca-actions">
                    <Link
                      className="ca-link"
                      to={`/clients/${c.id}/edit`}
                      aria-label={`Edit ${clientName(c)}`}
                    >
                      Edit
                    </Link>
                    {isManager && features.removeClient && (
                      <button
                        type="button"
                        className="ca-link danger"
                        onClick={() => openRemove(c)}
                        aria-label={`Remove ${clientName(c)}`}
                      >
                        Remove
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <dialog
        ref={dialogRef}
        className="ca-dialog"
        onClose={() => setTarget(null)}
        aria-labelledby="ca-title"
      >
        {target && (
          <form
            method="dialog"
            onSubmit={(e) => {
              e.preventDefault();
              void confirmRemove();
            }}
          >
            <h2 id="ca-title">Remove {clientName(target)}?</h2>
            <p className="muted">
              ID number <span className="mono">{target.idNumberMasked}</span>. The client is
              archived, not erased, and a manager can restore them.
            </p>
            {removeError && (
              <p className="alert error" role="alert">
                {removeError}
              </p>
            )}
            <label htmlFor="ca-reason">Reason</label>
            <input
              id="ca-reason"
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={200}
              autoComplete="off"
            />
            <p className="ca-hint">
              For example: captured twice. Do not type ID numbers or other client details.
            </p>
            <div className="ca-buttons">
              <button
                type="submit"
                className="btn danger-solid"
                disabled={removing || reason.trim().length < 3}
              >
                {removing ? 'Removing...' : 'Remove client'}
              </button>
              <button
                type="button"
                className="btn"
                onClick={() => setTarget(null)}
                disabled={removing}
              >
                Cancel
              </button>
            </div>
          </form>
        )}
      </dialog>
    </>
  );
}
