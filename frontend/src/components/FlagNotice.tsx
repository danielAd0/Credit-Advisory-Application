import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { transactions, type SaleRow } from '../api';
import { onWake, recentlyActive } from '../auth/activity';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './flagnotice.css';

const CHECK_EVERY_MS = 60_000;
const SHOWN = 5;

function dayText(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

function flagId(sale: SaleRow): string {
  return `${sale.id}:${sale.flag?.flaggedAt ?? ''}`;
}

function readSeen(storeKey: string): string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(storeKey) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : [];
  } catch {
    return [];
  }
}

function saveSeen(storeKey: string, ids: string[]) {
  try {
    localStorage.setItem(storeKey, JSON.stringify(ids.slice(-200)));
  } catch {
    return;
  }
}

export default function FlagNotice() {
  const session = useSession();
  const isConsultant = hasAnyRole(session, ['CONSULTANT']);
  const storeKey = `ca.flagsSeen.${session.email}`;
  const [fresh, setFresh] = useState<SaleRow[]>([]);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!isConsultant) return;
    let live = true;

    const check = () => {
      if (!recentlyActive()) return;
      const now = new Date();
      const yearAgo = new Date(now);
      yearAgo.setFullYear(now.getFullYear() - 1);
      transactions
        .list({ from: dayText(yearAgo), to: dayText(now), flagged: true }, 0, 50)
        .then((page) => {
          if (!live) return;
          const seen = readSeen(storeKey);
          setFresh(page.items.filter((s) => s.flag && !seen.includes(flagId(s))));
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
  }, [isConsultant, storeKey]);

  const open = fresh.length > 0;

  function dismiss() {
    saveSeen(storeKey, [...readSeen(storeKey), ...fresh.map(flagId)]);
    setFresh([]);
  }

  useEffect(() => {
    if (!open) return;
    closeButton.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      saveSeen(storeKey, [...readSeen(storeKey), ...fresh.map(flagId)]);
      setFresh([]);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, fresh, storeKey]);

  if (!open) return null;

  const more = fresh.length - SHOWN;

  return (
    <div className="fn-backdrop">
      <div className="fn-box" role="alertdialog" aria-modal="true" aria-labelledby="fn-title">
        <h2 id="fn-title">
          {fresh.length === 1
            ? 'A sale needs your correction'
            : `${fresh.length} sales need your correction`}
        </h2>
        <p className="fn-lead">
          A manager has asked you to check {fresh.length === 1 ? 'this sale' : 'these sales'}. Open
          {fresh.length === 1 ? ' it' : ' one'}, fix what is wrong and save. The request clears when
          you save.
        </p>
        <ul className="fn-list">
          {fresh.slice(0, SHOWN).map((s) => (
            <li key={flagId(s)}>
              <Link to={`/clients/${s.clientId}`} onClick={dismiss}>
                {s.clientName}
              </Link>
              <span className="fn-date">Sale of {s.transactionDate}</span>
              <p>{s.flag?.note}</p>
              <small>Asked by {s.flag?.flaggedBy}</small>
            </li>
          ))}
        </ul>
        {more > 0 && (
          <p className="fn-more">
            And {more} more. Tick "Needs correction only" in Sales history to see them all.
          </p>
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
