import { Fragment, useEffect, useState } from 'react';
import { detailOf, transactions, type SavedTransaction } from '../api';
import SaleEditor from './SaleEditor';
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
  const [loaded, setLoaded] = useState<{
    id: number;
    sales: SavedTransaction[] | null;
    error: string | null;
  } | null>(null);
  const [reload, setReload] = useState(0);
  const [editing, setEditing] = useState<number | null>(null);
  const [asking, setAsking] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

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

  function saved() {
    setEditing(null);
    setActionError(null);
    setReload((n) => n + 1);
  }

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

  return (
    <section className="cd-card cds" aria-labelledby="cds-h">
      <h2 id="cds-h">Sales</h2>
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
          <div className="table-wrap">
            <table className="cds-table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Products</th>
                  <th className="num">Quotation</th>
                  <th className="num">Banked</th>
                  <th>Source</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
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
                              <li key={l.productId}>
                                <span>{l.name}</span>
                                <span className="muted">{rand(l.amount)}</span>
                              </li>
                            ))}
                          </ul>
                        </td>
                        <td className="num">{rand(s.quotationTotal)}</td>
                        <td className="num">{rand(s.bankedTotal)}</td>
                        <td>{from.label}</td>
                        <td>
                          <div className="cds-actions">
                            {canChange(s) && asking !== s.id && (
                              <>
                                <button
                                  type="button"
                                  className="btn"
                                  onClick={() => {
                                    setEditing(editing === s.id ? null : s.id);
                                    setAsking(null);
                                    setActionError(null);
                                  }}
                                >
                                  {editing === s.id ? 'Close' : 'Edit'}
                                </button>
                                <button
                                  type="button"
                                  className="btn"
                                  onClick={() => {
                                    setAsking(s.id);
                                    setEditing(null);
                                    setActionError(null);
                                  }}
                                >
                                  Delete
                                </button>
                              </>
                            )}
                            {asking === s.id && (
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
                      </tr>
                      {editing === s.id && (
                        <tr className="cds-edit">
                          <td colSpan={6}>
                            <SaleEditor
                              saleId={s.id}
                              onSaved={saved}
                              onCancel={() => setEditing(null)}
                            />
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
