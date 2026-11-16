import { Fragment, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { detailOf, transactions, type SavedTransaction } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './clientsales.css';

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', minimumFractionDigits: 2 });

const day = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString('en-ZA', {
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });

function source(s: SavedTransaction): { label: string; muted: boolean } {
  if (s.typedIn === false) return { label: 'Imported', muted: false };
  if (s.replaced) return { label: 'Replaced by the spreadsheet', muted: true };
  return { label: 'Typed in', muted: false };
}

const canChange = (s: SavedTransaction) => s.typedIn !== false && s.replaced !== true;

export default function ClientSales({ clientId }: { clientId: number }) {
  const session = useSession();

  const consultant = hasAnyRole(session, ['CONSULTANT']);
  const manager = hasAnyRole(session, ['ADMIN', 'MANAGER']);

  const [loaded, setLoaded] = useState<{
    id: number;
    sales: SavedTransaction[] | null;
    error: string | null;
  } | null>(null);
  const [reload, setReload] = useState(0);
  const [asking, setAsking] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [flagging, setFlagging] = useState<number | null>(null);
  const [note, setNote] = useState('');

  useEffect(() => {
    let live = true;
    transactions
      .forClient(clientId)
      .then((sales) => live && setLoaded({ id: clientId, sales, error: null }))
      .catch(
        (err: unknown) =>
          live &&
          setLoaded({
            id: clientId,
            sales: null,
            error: detailOf(err) ?? 'Could not load the sales.',
          }),
      );
    return () => {
      live = false;
    };
  }, [clientId, reload]);

  const current = loaded && loaded.id === clientId ? loaded : null;

  async function remove(id: number) {
    setBusy(true);
    setActionError(null);
    try {
      const sale = await transactions.get(id);
      if (!sale.editable) {
        setActionError('This sale can no longer be deleted: its week has been imported.');
        setAsking(null);
      } else {
        await transactions.remove(id, sale.version);
        setAsking(null);
        setReload((n) => n + 1);
      }
    } catch (err) {
      setActionError(detailOf(err) ?? 'Could not delete the sale.');
    } finally {
      setBusy(false);
    }
  }

  async function flag(id: number) {
    if (note.trim() === '') {
      setActionError('Tell the consultant what to correct.');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await transactions.flag(id, note.trim());
      setFlagging(null);
      setNote('');
      setReload((n) => n + 1);
    } catch (err) {
      setActionError(detailOf(err) ?? 'Could not flag the sale.');
    } finally {
      setBusy(false);
    }
  }

  async function withdraw(id: number) {
    setBusy(true);
    setActionError(null);
    try {
      await transactions.unflag(id);
      setReload((n) => n + 1);
    } catch (err) {
      setActionError(detailOf(err) ?? 'Could not withdraw the flag.');
    } finally {
      setBusy(false);
    }
  }

  const showActions = consultant || manager;

  return (
    <section className="cd-card cds" aria-labelledby="cds-h">
      <div className="cd-head">
        <h2 id="cds-h">Sales</h2>
        {current?.sales && (
          <span className="cd-count">
            {current.sales.length} {current.sales.length === 1 ? 'sale' : 'sales'}
          </span>
        )}
        <span className="cd-meta">All amounts in ZAR</span>
      </div>
      {current === null && <p className="muted">Loading...</p>}
      {current?.error && (
        <p className="alert error" role="alert">
          {current.error}
        </p>
      )}
      {actionError && (
        <p className="alert error" role="alert">
          {actionError}
        </p>
      )}
      {current?.sales && current.sales.length === 0 && (
        <p className="muted">No sales for this client yet.</p>
      )}
      {current?.sales && current.sales.length > 0 && (
        <>
          <div className="table-wrap cd-table">
            <table className="cds-table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Products</th>
                  <th className="num">Amount</th>
                  <th className="num">Quotation</th>
                  <th className="num">Banked</th>
                  <th>Source</th>
                  {showActions && (
                    <th>
                      <span className="sr-only">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {current.sales.map((s) => {
                  const from = source(s);
                  return (
                    <Fragment key={s.id}>
                      <tr className={from.muted ? 'cds-muted' : undefined}>
                        <td>{day(s.transactionDate)}</td>
                        <td className="cds-products">
                          <ul className="cds-lines">
                            {s.lines.map((l) => (
                              <li key={l.productId}>{l.name}</li>
                            ))}
                          </ul>
                        </td>
                        <td className="num">
                          <ul className="cds-lines">
                            {s.lines.map((l) => (
                              <li key={l.productId}>{rand(l.amount)}</li>
                            ))}
                          </ul>
                        </td>
                        <td className="num cds-total">{rand(s.quotationTotal)}</td>
                        <td className="num cds-total">{rand(s.bankedTotal)}</td>
                        <td>
                          <span className="cd-pill">{from.label}</span>
                          {s.flag && (
                            <div className="cds-flag">
                              <span className="cds-flag-tag">Needs correction</span>
                              <p>{s.flag.note}</p>
                              <span className="muted">Asked by {s.flag.flaggedBy}</span>
                            </div>
                          )}
                        </td>
                        {showActions && (
                          <td>
                            <div className="cds-actions">
                              {canChange(s) && consultant && (
                                <Link className="btn" to={`/consultant-actions?saleId=${s.id}`}>
                                  Edit
                                </Link>
                              )}
                              {canChange(s) && manager && !s.flag && flagging !== s.id && (
                                <button
                                  type="button"
                                  className="btn"
                                  onClick={() => {
                                    setFlagging(s.id);
                                    setNote('');
                                    setAsking(null);
                                    setActionError(null);
                                  }}
                                >
                                  Flag for correction
                                </button>
                              )}
                              {canChange(s) && manager && s.flag && (
                                <button
                                  type="button"
                                  className="btn"
                                  disabled={busy}
                                  onClick={() => void withdraw(s.id)}
                                >
                                  Withdraw flag
                                </button>
                              )}
                              {canChange(s) && manager && asking !== s.id && (
                                <button
                                  type="button"
                                  className="btn"
                                  onClick={() => {
                                    setAsking(s.id);
                                    setFlagging(null);
                                    setActionError(null);
                                  }}
                                >
                                  Delete
                                </button>
                              )}
                              {manager && asking === s.id && (
                                <>
                                  <span>Delete this sale?</span>
                                  <button
                                    type="button"
                                    className="btn primary"
                                    disabled={busy}
                                    onClick={() => void remove(s.id)}
                                  >
                                    {busy ? 'Deleting...' : 'Yes, delete'}
                                  </button>
                                  <button
                                    type="button"
                                    className="btn"
                                    disabled={busy}
                                    onClick={() => setAsking(null)}
                                  >
                                    No
                                  </button>
                                </>
                              )}
                            </div>
                          </td>
                        )}
                      </tr>
                      {manager && flagging === s.id && (
                        <tr className="cds-flag-row">
                          <td colSpan={showActions ? 7 : 6}>
                            <div className="cds-flag-panel">
                              <h3>Flag this sale for correction</h3>
                              <p className="muted">
                                The consultant will see your note and fix the sale. The flag clears
                                when they save their changes.
                              </p>
                              <label htmlFor={`cds-note-${s.id}`}>What should be corrected?</label>
                              <textarea
                                id={`cds-note-${s.id}`}
                                rows={3}
                                maxLength={300}
                                placeholder="For example: the Insurance amount looks wrong."
                                value={note}
                                onChange={(e) => setNote(e.target.value)}
                              />
                              <div className="cds-flag-foot">
                                <span className="muted">{note.length} / 300</span>
                                <div className="cds-actions">
                                  <button
                                    type="button"
                                    className="btn"
                                    disabled={busy}
                                    onClick={() => setFlagging(null)}
                                  >
                                    Cancel
                                  </button>
                                  <button
                                    type="button"
                                    className="btn primary"
                                    disabled={busy || note.trim() === ''}
                                    onClick={() => void flag(s.id)}
                                  >
                                    {busy ? 'Sending...' : 'Send to consultant'}
                                  </button>
                                </div>
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
          {current.sales.length >= 200 && <p className="muted">Showing the latest 200 sales.</p>}
        </>
      )}
    </section>
  );
}
