import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, data, detailOf, isStale, type CaseDetail, type CaseListItem } from '../api';
import './cases.css';

const STATUSES = ['OPEN', 'IN_PROGRESS', 'CLOSED'];

/** Shows the status as plain readable text. An unknown value is shown as it is, never an error. */
const statusLabel = (s: string) => s.toLowerCase().replace(/_/g, ' ');

const dateTime = (iso: string) =>
  new Date(iso).toLocaleString('en-ZA', { dateStyle: 'medium', timeStyle: 'short' });

const who = (u: { email: string; consultantName: string | null }) => u.consultantName ?? u.email;

function failureText(err: unknown, fallback: string): string {
  if (isStale(err))
    return 'This case was changed by someone else. It has been reloaded, so try again.';
  if (err instanceof ApiError && err.status === 404)
    return 'This case no longer exists, or you do not have access to it.';
  return detailOf(err) ?? fallback;
}

function CasePanel({ caseId, onChanged }: { caseId: number; onChanged: () => void }) {
  const [loaded, setLoaded] = useState<{
    id: number;
    version: number;
    detail: CaseDetail | null;
  } | null>(null);
  const [reload, setReload] = useState(0);
  const [status, setStatus] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    data
      .getCase(caseId)
      .then((detail) => {
        if (!current) return;
        setLoaded({ id: caseId, version: reload, detail });
        setStatus(detail.status);
      })
      .catch(() => current && setLoaded({ id: caseId, version: reload, detail: null }));
    return () => {
      current = false;
    };
  }, [caseId, reload]);

  const detail =
    loaded && loaded.id === caseId && loaded.version === reload ? loaded.detail : undefined;

  if (detail === undefined) return <p className="muted">Loading...</p>;
  if (detail === null)
    return (
      <p className="alert error" role="alert">
        Could not load this case.
      </p>
    );

  const closed = detail.status === 'CLOSED';
  const statuses = STATUSES.includes(detail.status) ? STATUSES : [detail.status, ...STATUSES];

  async function saveStatus() {
    if (!detail || status === detail.status) return;
    setBusy(true);
    setError(null);
    try {
      await data.updateCase(detail.id, { title: detail.title, status, version: detail.version });
      onChanged();
    } catch (err) {
      setError(failureText(err, 'Could not change the status. Nothing was changed.'));
    } finally {
      setReload((n) => n + 1);
      setBusy(false);
    }
  }

  async function addNote(e: FormEvent) {
    e.preventDefault();
    if (!detail || !note.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await data.addCaseNote(detail.id, note.trim());
      setNote('');
      setReload((n) => n + 1);
    } catch (err) {
      setError(failureText(err, 'Could not add the note. Try again.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="cs-panel">
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}

      <div className="cs-status">
        <label htmlFor={`cs-status-${detail.id}`}>Status</label>
        <select
          id={`cs-status-${detail.id}`}
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          disabled={busy}
        >
          {statuses.map((s) => (
            <option key={s} value={s}>
              {statusLabel(s)}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="btn"
          onClick={() => void saveStatus()}
          disabled={busy || status === detail.status}
        >
          Save status
        </button>
      </div>

      <h4>Notes</h4>
      <p className="muted cs-hint">
        Notes cannot be edited or deleted. To correct something, add a new note.
      </p>
      {closed ? (
        <p className="muted">This case is closed. Reopen it to add a note.</p>
      ) : (
        <form className="cs-note-form" onSubmit={addNote}>
          <label htmlFor={`cs-note-${detail.id}`} className="cs-sr">
            New note
          </label>
          <textarea
            id={`cs-note-${detail.id}`}
            rows={3}
            maxLength={4000}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="Write a note"
          />
          <button type="submit" className="btn primary" disabled={busy || !note.trim()}>
            Add note
          </button>
        </form>
      )}

      {detail.notes.length === 0 ? (
        <p className="muted">No notes yet.</p>
      ) : (
        <ul className="cs-notes">
          {detail.notes.map((n) => (
            <li key={n.id}>
              <p>{n.note}</p>
              <span className="muted">
                {who(n.author)}, {dateTime(n.createdAt)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function ClientCases({ clientId }: { clientId: number }) {
  const [list, setList] = useState<{
    clientId: number;
    version: number;
    rows: CaseListItem[] | null;
  } | null>(null);
  const [version, setVersion] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [title, setTitle] = useState('');
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    data
      .listCases(clientId)
      .then((rows) => current && setList({ clientId, version, rows }))
      .catch(() => current && setList({ clientId, version, rows: null }));
    return () => {
      current = false;
    };
  }, [clientId, version]);

  const rows =
    list && list.clientId === clientId && list.version === version ? list.rows : undefined;

  async function addCase(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setAdding(true);
    setError(null);
    try {
      const created = await data.createCase(clientId, title.trim());
      setTitle('');
      setOpenId(created.id);
      setVersion((v) => v + 1);
    } catch (err) {
      setError(detailOf(err) ?? 'Could not add the case. Try again.');
    } finally {
      setAdding(false);
    }
  }

  return (
    <section className="cd-card cs" aria-labelledby="cs-title">
      <h2 id="cs-title">Cases</h2>

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}

      <form className="cs-new" onSubmit={addCase}>
        <label htmlFor="cs-new-title" className="cs-sr">
          Case title
        </label>
        <input
          id="cs-new-title"
          type="text"
          maxLength={200}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="New case title, for example Debt review application"
          autoComplete="off"
        />
        <button type="submit" className="btn primary" disabled={adding || !title.trim()}>
          {adding ? 'Adding...' : 'Add case'}
        </button>
      </form>

      {rows === undefined && <p className="muted">Loading...</p>}
      {rows === null && <p className="muted">Could not load the cases.</p>}
      {rows && rows.length === 0 && <p className="muted">No cases yet.</p>}

      {rows && rows.length > 0 && (
        <ul className="cs-list">
          {rows.map((c) => (
            <li key={c.id}>
              <button
                type="button"
                className="cs-row"
                onClick={() => setOpenId(openId === c.id ? null : c.id)}
                aria-expanded={openId === c.id}
              >
                <span className="cs-title">{c.title}</span>
                <span
                  className={`badge cs-badge ${c.status === 'IN_PROGRESS' ? 'active' : c.status === 'CLOSED' ? 'closed' : ''}`}
                >
                  {statusLabel(c.status)}
                </span>
                <span className="muted">
                  Opened {new Date(c.openedAt).toLocaleDateString('en-ZA')}
                </span>
              </button>
              {openId === c.id && (
                <CasePanel caseId={c.id} onChanged={() => setVersion((v) => v + 1)} />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
