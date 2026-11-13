import { useEffect, useState, type FormEvent } from 'react';
import { detailOf, targets, type TargetsResponse } from '../api';
import './addclient.css';
import './targets.css';

const rand = (n: number | null) =>
  n === null
    ? '-'
    : n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', minimumFractionDigits: 2 });

const dayLabel = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString('en-ZA', {
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });

/** The first Monday on or after the date: a weekly target applies to weeks starting on or after it. */
function firstMonday(iso: string) {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + ((8 - d.getDay()) % 7));
  return d.toLocaleDateString('en-ZA', { day: '2-digit', month: 'short', year: 'numeric' });
}

/** Accepts "150000", "150 000", "R150 000" or "150000.50". */
const parseAmount = (s: string) => (s.trim() === '' ? NaN : Number(s.replace(/[R\s]/gi, '')));

export default function TargetsPage() {
  const [data, setData] = useState<TargetsResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  const [from, setFrom] = useState('');
  const [daily, setDaily] = useState('');
  const [weekly, setWeekly] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    targets
      .list()
      .then((r) => {
        if (!live) return;
        setData(r);
        setLoadError(null);
        setFrom((f) => f || r.today);
      })
      .catch((err: unknown) => {
        if (live) setLoadError(detailOf(err) ?? 'Could not load the targets.');
      });
    return () => {
      live = false;
    };
  }, [reload]);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (busy || !data) return;
    setError(null);
    setOk(null);
    const d = parseAmount(daily);
    const w = parseAmount(weekly);
    if (!from) return setError('Choose the date the new targets start.');
    if (first && from < data.today && from >= first.effectiveFrom) return setError(pastDateRule);
    if (!Number.isFinite(d) || d <= 0) return setError('Enter a daily target above R0.');
    if (!Number.isFinite(w) || w <= 0) return setError('Enter a weekly target above R0.');
    if (w < d) return setError('The weekly target cannot be less than the daily target.');
    setBusy(true);
    try {
      const saved = await targets.set({
        effectiveFrom: from,
        dailyTarget: Math.round(d * 100) / 100,
        weeklyTarget: Math.round(w * 100) / 100,
      });
      setOk(
        `Saved. Daily target ${rand(saved.dailyTarget)} from ${dayLabel(saved.effectiveFrom)}; ` +
          `weekly target ${rand(saved.weeklyTarget)} from the week starting ${firstMonday(saved.effectiveFrom)}.`,
      );
      setDaily('');
      setWeekly('');
      setReload((n) => n + 1);
    } catch (err) {
      setError(detailOf(err) ?? 'Could not save the targets.');
    } finally {
      setBusy(false);
    }
  }

  const current = data?.current ?? null;
  // A past date can only get a target while none applies to it: before the
  // first target ever saved. With no targets at all, any date is fine.
  const first = data && data.history.length > 0 ? data.history[data.history.length - 1] : null;
  const pastDateRule = first
    ? `Targets already apply from ${dayLabel(first.effectiveFrom)}, so a change must start today or later. ` +
      `A date before ${dayLabel(first.effectiveFrom)} can still be given a target.`
    : '';
  const pastAllowedBefore = first ? first.effectiveFrom : null;
  // history is newest first, so the last scheduled row is the next one to take effect.
  const scheduled = data ? data.history.filter((t) => t.effectiveFrom > data.today) : [];
  const next = scheduled.length > 0 ? scheduled[scheduled.length - 1] : null;

  return (
    <div className="tg">
      <h1>Sales targets</h1>
      <p className="muted">
        The quotation targets the reports measure against. There are no built-in targets: until one
        is saved here, reports show “Target not configured”. A new target applies from its start
        date onward; weeks that already had a target keep it.
      </p>

      {loadError && (
        <p className="alert error" role="alert">
          {loadError}
        </p>
      )}
      {!loadError && !data && <p className="muted">Loading...</p>}

      {data && (
        <>
          {!current && (
            <p className="alert" role="status">
              {data.history.length === 0
                ? 'No targets configured. Reports show actual sales only until a daily and weekly target is saved below.'
                : 'No target is configured for today yet. Reports from before the first target show “Target not configured”.'}
            </p>
          )}

          <div className="tg-tiles">
            <div className="tg-tile">
              <span>Current daily target</span>
              <strong>{current ? rand(current.dailyTarget) : 'Not configured'}</strong>
            </div>
            <div className="tg-tile">
              <span>Current weekly target</span>
              <strong>
                {current?.weeklyTarget != null ? rand(current.weeklyTarget) : 'Not configured'}
              </strong>
            </div>
            <div className="tg-tile">
              <span>In force since</span>
              <strong>{current ? dayLabel(current.effectiveFrom) : '-'}</strong>
            </div>
          </div>

          {next && (
            <p className="alert ok" role="status">
              Next change: {rand(next.dailyTarget)} daily and {rand(next.weeklyTarget)} weekly from{' '}
              {dayLabel(next.effectiveFrom)}.
            </p>
          )}

          <section className="tg-form" aria-label="Set new targets">
            <h2>Set new targets</h2>
            {error && (
              <p className="alert error" role="alert">
                {error}
              </p>
            )}
            {ok && (
              <p className="alert ok" role="status">
                {ok}
              </p>
            )}
            <form onSubmit={save} noValidate>
              <div className="ac-row ac-row-3">
                <div className="ac-field">
                  <label htmlFor="tg-daily">Daily target (R)</label>
                  <input
                    id="tg-daily"
                    inputMode="decimal"
                    placeholder={current ? String(current.dailyTarget) : 'Amount in rand'}
                    value={daily}
                    onChange={(e) => setDaily(e.target.value)}
                    autoComplete="off"
                  />
                </div>
                <div className="ac-field">
                  <label htmlFor="tg-weekly">Weekly target (R)</label>
                  <input
                    id="tg-weekly"
                    inputMode="decimal"
                    placeholder={
                      current?.weeklyTarget ? String(current.weeklyTarget) : 'Amount in rand'
                    }
                    value={weekly}
                    onChange={(e) => setWeekly(e.target.value)}
                    autoComplete="off"
                  />
                </div>
                <div className="ac-field">
                  <label htmlFor="tg-from">Starts on</label>
                  <input
                    id="tg-from"
                    type="date"
                    value={from}
                    onChange={(e) => setFrom(e.target.value)}
                  />
                </div>
              </div>
              {pastAllowedBefore ? (
                <p className="ac-hint">
                  Dates before {dayLabel(pastAllowedBefore)} have no target yet, so they can still
                  be given one (for example after importing older registers).
                </p>
              ) : (
                !first && (
                  <p className="ac-hint">
                    Any date can be chosen, including a past one, to set targets for registers
                    already imported.
                  </p>
                )
              )}
              {from && (
                <p className="ac-hint">
                  The daily target counts from {dayLabel(from)}. The weekly target counts from the
                  week starting {firstMonday(from)}. Saving again for the same date replaces it.
                </p>
              )}
              <div className="cn-actions">
                <button type="submit" className="btn primary" disabled={busy}>
                  {busy ? 'Saving...' : 'Save targets'}
                </button>
              </div>
            </form>
          </section>

          <section className="tg-history">
            <h2>History</h2>
            {data.history.length === 0 && <p className="muted">No targets have been saved yet.</p>}
            <div className="table-wrap">
              <table className="tg-table">
                <thead>
                  <tr>
                    <th>Starts on</th>
                    <th className="num">Daily</th>
                    <th className="num">Weekly</th>
                    <th>Set by</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {data.history.map((t) => (
                    <tr key={t.id}>
                      <td>{dayLabel(t.effectiveFrom)}</td>
                      <td className="num">{rand(t.dailyTarget)}</td>
                      <td className="num">{rand(t.weeklyTarget)}</td>
                      <td>{t.setByEmail ?? '-'}</td>
                      <td>
                        {t.id === current?.id ? (
                          <span className="badge active">current</span>
                        ) : t.effectiveFrom > data.today ? (
                          <span className="badge">scheduled</span>
                        ) : (
                          <span className="muted">past</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </div>
  );
}
