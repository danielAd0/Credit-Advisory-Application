import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, data, detailOf, features, type ClientSummary, type PageResponse } from '../api';
import { clientName, phoneForDisplay } from '../api/names';
import Pager from '../components/Pager';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './clientsactions.css';

/** An empty cell, so a missing value reads as missing rather than as a layout gap. */
const Dash = () => (
  <span className="muted" aria-label="none">
    -
  </span>
);

export default function ClientsPage() {
  const session = useSession();
  const isManager = hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [version, setVersion] = useState(0);
  const [result, setResult] = useState<{
    key: string;
    data: PageResponse<ClientSummary>;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [target, setTarget] = useState<ClientSummary | null>(null);
  const [reason, setReason] = useState('');
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);

  const key = `${query}|${page}|${version}`;

  useEffect(() => {
    let current = true;
    const t = setTimeout(() => {
      data
        .listClientsPage({ search: query }, page)
        .then((r) => {
          if (current) {
            setResult({ key, data: r });
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
  }, [query, page, key]);

  useEffect(() => {
    const d = dialogRef.current;
    if (!d) return;
    if (target && !d.open) d.showModal();
    if (!target && d.open) d.close();
  }, [target]);

  const paged = result && result.key === key ? result.data : null;
  const rows = paged ? paged.items : null;

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
      setNotice(
        `${clientName(target)} no longer has a consultant. You can assign them on the Reassign page.`,
      );
      setTarget(null);
      setPage(0);
      setVersion((v) => v + 1);
    } catch (err) {
      setRemoveError(
        detailOf(err) ??
          (err instanceof ApiError && err.status === 403
            ? 'You do not have permission to remove clients.'
            : err instanceof ApiError && err.status === 409
              ? 'This client has no consultant, or still has open cases.'
              : 'Could not remove the client. Nothing was changed.'),
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
          placeholder="Search by name, File number or File ID"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setPage(0);
          }}
          aria-label="Search clients"
          autoComplete="off"
        />
        {!isManager && (
          <Link className="btn primary" to="/clients/new">
            Add client
          </Link>
        )}
        {isManager && features.import && (
          <Link className="btn primary" to="/import">
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
          <table className="cl-table">
            <thead>
              <tr>
                <th>Surname</th>
                <th>Name</th>
                <th>Client employer</th>
                <th>Work telephone</th>
                <th>Home telephone</th>
                <th>Branch</th>
                <th>Payment term</th>
                <th>Structure</th>
                {isManager && <th>Consultant</th>}
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td>
                    <Link className="row-link" to={`/clients/${c.id}`}>
                      {c.lastName}
                    </Link>
                  </td>
                  <td>{c.firstName}</td>
                  <td>
                    {c.employer ? (
                      c.employer.name
                    ) : c.paymentMethod === 'DEBIT_ORDER' ? (
                      <span className="muted">Debit order</span>
                    ) : (
                      <Dash />
                    )}
                  </td>
                  <td>{c.workPhone ? phoneForDisplay(c.workPhone) : <Dash />}</td>
                  <td>{c.homePhone ? phoneForDisplay(c.homePhone) : <Dash />}</td>
                  <td>{c.productQuotation?.name ?? <Dash />}</td>
                  <td>{c.latestSale?.paymentTerm ?? <Dash />}</td>
                  <td>{c.latestSale?.structure ?? <Dash />}</td>
                  {isManager && (
                    <td>{c.consultant?.fullName ?? <span className="muted">Unassigned</span>}</td>
                  )}
                  <td className="ca-actions">
                    {!isManager && (
                      <>
                        <Link
                          className="ca-link"
                          to={`/consultant-actions?clientId=${c.id}`}
                          aria-label={`Capture a sale for ${clientName(c)}`}
                        >
                          Capture sale
                        </Link>
                        <Link
                          className="ca-link"
                          to={`/clients/${c.id}/edit`}
                          aria-label={`Edit ${clientName(c)}`}
                        >
                          Edit
                        </Link>
                      </>
                    )}
                    {isManager && features.removeClient && c.consultant && (
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
      {paged && (
        <Pager
          page={paged.page}
          size={paged.size}
          totalItems={paged.totalItems}
          totalPages={paged.totalPages}
          onPage={setPage}
        />
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
            <h2 id="ca-title">Remove {clientName(target)} from their consultant?</h2>
            <p className="muted">
              ID number <span className="mono">{target.idNumberMasked}</span>. The client stays in
              the system with all their details, but will no longer have a consultant. You can
              assign them to someone else later on the Reassign page.
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
              maxLength={500}
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
