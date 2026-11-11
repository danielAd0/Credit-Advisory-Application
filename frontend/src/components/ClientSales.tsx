import { useEffect, useState } from 'react';
import { detailOf, transactions, type SavedTransaction } from '../api';
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

export default function ClientSales({ clientId }: { clientId: number }) {
  const [loaded, setLoaded] = useState<{
    id: number;
    sales: SavedTransaction[] | null;
    error: string | null;
  } | null>(null);

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
  }, [clientId]);

  const current = loaded && loaded.id === clientId ? loaded : null;

  return (
    <section className="cd-card cds" aria-labelledby="cds-h">
      <h2 id="cds-h">Sales</h2>
      {current === null && <p className="muted">Loading...</p>}
      {current?.error && (
        <p className="alert error" role="alert">
          {current.error}
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
                </tr>
              </thead>
              <tbody>
                {current.sales.map((s) => {
                  const from = source(s);
                  return (
                    <tr key={s.id} className={from.muted ? 'cds-muted' : undefined}>
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
                    </tr>
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
