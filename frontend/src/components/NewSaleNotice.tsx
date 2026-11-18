import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { transactions, type SaleRow } from '../api';
import { onWake, recentlyActive } from '../auth/activity';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './flagnotice.css';

const CHECK_EVERY_MS = 60_000;
const SHOWN = 5;

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', minimumFractionDigits: 2 });

function dayText(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

function readSeen(storeKey: string): number | null {
  try {
    const value = Number(localStorage.getItem(storeKey));
    return Number.isFinite(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

function saveSeen(storeKey: string, id: number) {
  try {
    localStorage.setItem(storeKey, String(id));
  } catch {
    return;
  }
}

export default function NewSaleNotice() {
  const session = useSession();
  const isManager = hasAnyRole(session, ['MANAGER', 'ADMIN']);
  const storeKey = `ca.salesSeen.${session.email}`;
  const [fresh, setFresh] = useState<SaleRow[]>([]);
  const [newest, setNewest] = useState(0);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!isManager) return;
    let live = true;

    const check = () => {
      if (!recentlyActive()) return;
      const now = new Date();
      const weekAgo = new Date(now);
      weekAgo.setDate(now.getDate() - 7);
      const today = dayText(now);
      transactions
        .list({ from: dayText(weekAgo), to: today }, 0, 50)
        .then((page) => {
          if (!live) return;
          const typedIn = page.items.filter((s) => s.typedIn !== false);
          const lastSeen = readSeen(storeKey);
          const unseen = typedIn.filter((s) =>
            lastSeen === null ? s.transactionDate === today : s.id > lastSeen,
          );
          setFresh(unseen);
          setNewest(Math.max(lastSeen ?? 0, ...typedIn.map((s) => s.id)));
        })
        .catch(() => undefined);
    };

    check();
    const timer = window.setInterval(check, CHECK_EVERY_MS);
    window.addEventListener('focus', check);
    const stopWaiting = onWake(check);
    return () => {
      live = false;
      window.clearInterval(timer);
      window.removeEventListener('focus', check);
      stopWaiting();
    };
  }, [isManager, storeKey]);

  const open = fresh.length > 0;

  function dismiss() {
    saveSeen(storeKey, newest);
    setFresh([]);
  }

  useEffect(() => {
    if (!open) return;
    closeButton.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      saveSeen(storeKey, newest);
      setFresh([]);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, newest, storeKey]);

  if (!open) return null;

  const more = fresh.length - SHOWN;

  return (
    <div className="fn-backdrop">
      <div className="fn-box" role="alertdialog" aria-modal="true" aria-labelledby="ns-title">
        <h2 id="ns-title">
          {fresh.length === 1
            ? 'A new sale was captured'
            : `${fresh.length} new sales were captured`}
        </h2>
        <p className="fn-lead">
          {fresh.length === 1 ? 'A consultant has' : 'Consultants have'} typed in{' '}
          {fresh.length === 1 ? 'a sale' : 'sales'} since you last checked. If something looks
          wrong, open the sale and flag it for correction.
        </p>
        <ul className="fn-list">
          {fresh.slice(0, SHOWN).map((s) => (
            <li key={s.id}>
              <Link to={`/clients/${s.clientId}`} onClick={dismiss}>
                {s.clientName}
              </Link>
              <span className="fn-date">Sale of {s.transactionDate}</span>
              <p>
                Quotation {rand(s.quotationTotal)}, banked {rand(s.bankedTotal)}
              </p>
              <small>Captured by {s.consultantName}</small>
            </li>
          ))}
        </ul>
        {more > 0 && (
          <p className="fn-more">And {more} more. Open Sales history to see them all.</p>
        )}
        <div className="fn-foot">
          <Link to="/sales-history" className="fn-secondary" onClick={dismiss}>
            Open Sales history
          </Link>
          <button type="button" ref={closeButton} onClick={dismiss}>
            Got it
          </button>
        </div>
      </div>
    </div>
  );
}
