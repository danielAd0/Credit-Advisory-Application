import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { data, detailOf, transactions, type ConsultantSummary, type SalesPage } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import Pager from '../components/Pager';
import './saleshistory.css';

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', minimumFractionDigits: 2 });

const pad = (n: number) => String(n).padStart(2, '0');
const dayText = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const addDays = (d: Date, n: number) => {
  const out = new Date(d);
  out.setDate(out.getDate() + n);
  return out;
};

const mondayOf = (d: Date) => addDays(d, -((d.getDay() + 6) % 7));

const dayLabel = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString('en-ZA', {
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });

export default function SalesHistoryPage() {
  const session = useSession();
  const manager = hasAnyRole(session, ['ADMIN', 'MANAGER']);

  const [from, setFrom] = useState(() => dayText(mondayOf(new Date())));
  const [to, setTo] = useState(() => dayText(new Date()));
  const [consultantId, setConsultantId] = useState('');
  const [page, setPage] = useState(0);
  const [staff, setStaff] = useState<ConsultantSummary[]>([]);
  const [result, setResult] = useState<{ key: string; data: SalesPage } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const valid = from !== '' && to !== '' && from <= to;
  const key = `${from}|${to}|${consultantId}|${page}`;

  useEffect(() => {
    if (!manager) return;
    let live = true;
    data
      .listConsultants()
      .then((c) => live && setStaff(c))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [manager]);

  useEffect(() => {
    if (!valid) return;
    let live = true;
    transactions
      .list({ from, to, ...(consultantId ? { consultantId: Number(consultantId) } : {}) }, page)
      .then((r) => {
        if (!live) return;
        setResult({ key, data: r });
        setError(null);
      })
      .catch((err: unknown) => {
        if (live) setError(detailOf(err) ?? 'Could not load the sales.');
      });
    return () => {
      live = false;
    };
  }, [valid, from, to, consultantId, page, key]);

  const paged = valid && result && result.key === key ? result.data : null;

  function preset(start: Date, end: Date) {
    setFrom(dayText(start));
    setTo(dayText(end));
    setPage(0);
  }
  const today = new Date();
  const thisMonday = mondayOf(today);

  return (
    <div className="ms">
      <h1>Sales history</h1>
      <p className="muted">
        Every sale captured on the Consultant actions page.
        {manager ? '' : ' You see your own sales.'}
      </p>

      <div className="ms-filters">
        <div className="ms-field">
          <label htmlFor="ms-from">From</label>
          <input
            id="ms-from"
            type="date"
            max={to || undefined}
            value={from}
            onChange={(e) => {
              setFrom(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="ms-field">
          <label htmlFor="ms-to">To</label>
          <input
            id="ms-to"
            type="date"
            min={from || undefined}
            value={to}
            onChange={(e) => {
              setTo(e.target.value);
              setPage(0);
            }}
          />
        </div>
        {manager && (
          <div className="ms-field">
            <label htmlFor="ms-consultant">Consultant</label>
            <select
              id="ms-consultant"
              value={consultantId}
              onChange={(e) => {
                setConsultantId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All consultants</option>
              {staff.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.fullName} ({c.consultantCode})
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="ms-presets">
          <button type="button" className="btn" onClick={() => preset(thisMonday, today)}>
            This week
          </button>
          <button
            type="button"
            className="btn"
            onClick={() => preset(addDays(thisMonday, -7), addDays(thisMonday, -1))}
          >
            Last week
          </button>
          <button type="button" className="btn" onClick={() => preset(addDays(today, -29), today)}>
            Last 30 days
          </button>
        </div>
      </div>

      {!valid && (
        <p className="alert error" role="alert">
          Choose a start date that is on or before the end date.
        </p>
      )}
      {valid && error && !paged && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {valid && !error && !paged && <p className="muted">Loading...</p>}

      {paged && (
        <>
          <div className="ms-tiles">
            <div className="ms-tile">
              <span>Sales</span>
              <strong>{paged.totalItems}</strong>
            </div>
            <div className="ms-tile">
              <span>Quotation total</span>
              <strong>{rand(paged.quotationTotal)}</strong>
            </div>
            <div className="ms-tile">
              <span>Banked total</span>
              <strong>{rand(paged.bankedTotal)}</strong>
            </div>
          </div>

          {paged.items.length === 0 ? (
            <p className="muted">
              No sales in this period. <Link to="/consultant-actions">Capture a sale</Link>.
            </p>
          ) : (
            <div className="table-wrap">
              <table className="ms-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Client</th>
                    {manager && <th>Consultant</th>}
                    <th>Products</th>
                    <th className="num">Quotation</th>
                    <th className="num">Banked</th>
                  </tr>
                </thead>
                <tbody>
                  {paged.items.map((s) => (
                    <tr key={s.id}>
                      <td>{dayLabel(s.transactionDate)}</td>
                      <td>
                        <Link to={`/clients/${s.clientId}`}>{s.clientName}</Link>
                      </td>
                      {manager && <td>{s.consultantName}</td>}
                      <td className="ms-products">
                        <ul className="ms-lines">
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
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <Pager
            page={paged.page}
            size={paged.size}
            totalItems={paged.totalItems}
            totalPages={paged.totalPages}
            onPage={setPage}
          />
        </>
      )}
    </div>
  );
}
