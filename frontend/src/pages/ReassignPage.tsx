import { useEffect, useState, type FormEvent } from 'react';
import {
  ApiError,
  data,
  type ClientSummary,
  type ConsultantSummary,
  type MoveResult,
} from '../api';
import { clientName } from '../api/names';
import './reassign.css';

/** "none" = the clients nobody has yet. Otherwise the id of the consultant who has them today. */
const NONE = 'none';

function explain(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'You do not have permission to reassign clients.';
    if (err.status === 400)
      return 'Check the details. The reason is required and the two consultants must differ.';
    if (err.status === 409)
      return 'One or more of these clients was moved by someone else. The list has been reloaded. Choose the clients again.';
    if (err.status === 422)
      return 'That consultant cannot take clients right now. Choose another one.';
  }
  return 'Could not reassign the clients. Nothing was changed. Try again.';
}

export default function ReassignPage() {
  const [consultants, setConsultants] = useState<ConsultantSummary[]>([]);
  const [from, setFrom] = useState(NONE);
  const [query, setQuery] = useState('');
  const [version, setVersion] = useState(0); // bumped after a save so the list reloads
  const [result, setResult] = useState<{ key: string; rows: ClientSummary[] } | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [toId, setToId] = useState('');
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [done, setDone] = useState<MoveResult | null>(null);

  useEffect(() => {
    let current = true;
    data
      .listConsultants()
      .then((r) => current && setConsultants(r))
      .catch(() => current && setLoadError(true));
    return () => {
      current = false;
    };
  }, []);

  const key = `${from}|${query}|${version}`;

  useEffect(() => {
    let current = true;
    const t = setTimeout(() => {
      data
        .listClientsBy({
          search: query,
          unassigned: from === NONE ? true : undefined,
          consultantId: from === NONE ? undefined : Number(from),
        })
        .then((rows) => {
          if (current) {
            setResult({ key, rows });
            setLoadError(false);
          }
        })
        .catch(() => current && setLoadError(true));
    }, 250);
    return () => {
      current = false;
      clearTimeout(t);
    };
  }, [from, query, key]);

  const rows = result && result.key === key ? result.rows : null;
  const allShown = !!rows && rows.length > 0 && rows.every((c) => selected.has(c.id));
  const targets = consultants.filter((c) => c.status === 'ACTIVE' && String(c.id) !== from);
  const target = targets.find((c) => String(c.id) === toId);

  function changeFrom(value: string) {
    setFrom(value);
    setSelected(new Set()); // a handover is always from one consultant, so start again
    setToId('');
    setDone(null);
  }

  function toggle(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (!rows) return;
    setSelected((prev) => {
      const next = new Set(prev);
      for (const c of rows) {
        if (allShown) next.delete(c.id);
        else next.add(c.id);
      }
      return next;
    });
  }

  const ready = selected.size > 0 && toId !== '' && reason.trim().length >= 3 && !saving;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready) return;
    setSaving(true);
    setFailure(null);
    try {
      const r = await data.moveClients({
        fromConsultantId: from === NONE ? null : Number(from),
        toConsultantId: Number(toId),
        clientIds: [...selected],
        reason: reason.trim(),
      });
      setDone(r);
      setSelected(new Set());
      setReason('');
      setVersion((v) => v + 1);
    } catch (err) {
      setFailure(explain(err));
      if (err instanceof ApiError && err.status === 409) {
        setSelected(new Set());
        setVersion((v) => v + 1);
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="ra">
      <h1>Reassign clients</h1>
      <p className="muted">
        Choose whose clients to move, tick the ones to move, pick the consultant who takes them, and
        say why. The reason is kept in the assignment history.
      </p>

      {done && (
        <p className="alert ok" role="status">
          {done.moved} {done.moved === 1 ? 'client was' : 'clients were'} moved.
        </p>
      )}
      {(failure || loadError) && (
        <p className="alert error" role="alert">
          {failure ?? 'Could not load the list. Refresh and try again.'}
        </p>
      )}

      <div className="ra-tools">
        <div className="ra-from">
          <label htmlFor="ra-from">Move clients from</label>
          <select id="ra-from" value={from} onChange={(e) => changeFrom(e.target.value)}>
            <option value={NONE}>Not assigned to anyone</option>
            {consultants.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName}
                {c.status !== 'ACTIVE' ? ` (${c.status.toLowerCase()})` : ''}
              </option>
            ))}
          </select>
        </div>
        <input
          type="search"
          placeholder="Search by name"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search clients"
          autoComplete="off"
        />
      </div>

      {rows === null && !loadError && <p className="muted">Loading...</p>}
      {rows && rows.length === 0 && <p className="muted">No clients here.</p>}

      {rows && rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th className="ra-box">
                  <input
                    type="checkbox"
                    checked={allShown}
                    onChange={toggleAll}
                    aria-label="Select all shown clients"
                  />
                </th>
                <th>Name</th>
                <th>ID number</th>
                <th>Current consultant</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className={selected.has(c.id) ? 'ra-picked' : undefined}>
                  <td className="ra-box">
                    <input
                      type="checkbox"
                      checked={selected.has(c.id)}
                      onChange={() => toggle(c.id)}
                      aria-label={`Select ${clientName(c)}`}
                    />
                  </td>
                  <td>{clientName(c)}</td>
                  <td className="mono">{c.idNumberMasked}</td>
                  <td>{c.consultant?.fullName ?? <span className="muted">Unassigned</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <form className="ra-form" onSubmit={submit}>
        <h2>
          Move{' '}
          {selected.size === 0
            ? 'selected clients'
            : `${selected.size} ${selected.size === 1 ? 'client' : 'clients'}`}
        </h2>
        <div className="ra-field">
          <label htmlFor="ra-to">New consultant</label>
          <select id="ra-to" value={toId} onChange={(e) => setToId(e.target.value)}>
            <option value="">Choose a consultant</option>
            {targets.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName}
              </option>
            ))}
          </select>
        </div>
        <div className="ra-field">
          <label htmlFor="ra-reason">Reason</label>
          <input
            id="ra-reason"
            type="text"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={200}
            autoComplete="off"
          />
          <p className="ra-hint">
            For example: consultant on leave. Do not type ID numbers or other client details here.
          </p>
        </div>
        <button type="submit" className="btn primary" disabled={!ready}>
          {saving ? 'Saving...' : target ? `Move to ${target.fullName}` : 'Move clients'}
        </button>
      </form>
    </div>
  );
}
