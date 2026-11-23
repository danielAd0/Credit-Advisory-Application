import { useEffect, useState } from 'react';
import {
  catalogue,
  detailOf,
  isStale,
  type EmployerDuplicateGroup,
  type EmployerDuplicateSuggestions,
} from '../api';
import './employerduplicates.css';

const SHOWN = 5;
const DEFAULT_REASON = 'Same employer: spelling variant';

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const keyOf = (g: EmployerDuplicateGroup) => String(g.suggestedTargetId ?? g.employers[0]?.id);

interface Props {
  reload: number;
  canMerge: boolean;
  onMerged: () => void;
}

export default function EmployerDuplicates({ reload, canMerge, onMerged }: Props) {
  const [data, setData] = useState<EmployerDuplicateSuggestions | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reason, setReason] = useState(DEFAULT_REASON);
  const [unticked, setUnticked] = useState<number[]>([]);
  const [asking, setAsking] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [all, setAll] = useState(false);

  useEffect(() => {
    let live = true;
    catalogue
      .duplicateSuggestions()
      .then((r) => live && (setData(r), setError(null)))
      .catch(() => live && setError('Could not load the duplicate suggestions.'));
    return () => {
      live = false;
    };
  }, [reload]);

  const picked = (g: EmployerDuplicateGroup) =>
    g.employers.filter((e) => e.id !== g.suggestedTargetId && !unticked.includes(e.id));

  const toggle = (id: number) =>
    setUnticked((u) => (u.includes(id) ? u.filter((x) => x !== id) : [...u, id]));

  async function merge(g: EmployerDuplicateGroup) {
    if (busy !== null || g.suggestedTargetId === null) return;
    const others = picked(g);
    if (others.length === 0) return;
    if (!reason.trim()) {
      setAsking(null);
      return setError('Give a reason for the merge.');
    }
    setBusy(keyOf(g));
    setError(null);
    setNotice(null);
    try {
      const result = await catalogue.mergeEmployerGroup(g.suggestedTargetId, {
        employers: others.map((e) => ({ id: e.id, version: e.version })),
        reason: reason.trim(),
      });
      setNotice(
        `Merged ${plural(result.merged.length, 'employer', 'employers')} into ${result.target.name}: ` +
          `${plural(result.clientsMoved, 'client', 'clients')} and ` +
          `${plural(result.transactionsMoved, 'sale', 'sales')} moved.`,
      );
      setAsking(null);
      onMerged();
    } catch (err) {
      setError(
        isStale(err)
          ? `${detailOf(err) ?? 'One of these employers was changed.'} The list has been reloaded: check it and try again.`
          : (detailOf(err) ?? 'Could not merge the group.'),
      );
      setAsking(null);
      onMerged();
    } finally {
      setBusy(null);
    }
  }

  const groups = data?.groups ?? [];
  if (!error && !notice && groups.length === 0) return null;

  const shown = all ? groups : groups.slice(0, SHOWN);

  return (
    <section className="lp-card ed-card" aria-label="Possible duplicates">
      <div className="lp-head">
        <h2>Possible duplicates</h2>
        {data && (
          <span className="lp-count">
            {plural(data.groupCount, 'group', 'groups')},{' '}
            {plural(data.employerCount, 'employer', 'employers')}
          </span>
        )}
      </div>
      <p className="ac-hint">
        These employers look like the same one spelled differently. Tick the names that belong, then
        merge them into the one to keep. Clients and sales move to it.
      </p>

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

      {canMerge && groups.length > 0 && (
        <div className="ac-field ed-reason">
          <label htmlFor="ed-reason">Reason for the merges</label>
          <input
            id="ed-reason"
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
            autoComplete="off"
          />
        </div>
      )}

      <ul className="ed-groups">
        {shown.map((g) => {
          const key = keyOf(g);
          const target = g.employers.find((e) => e.id === g.suggestedTargetId) ?? null;
          const others = picked(g);
          return (
            <li key={key} className="ed-group">
              <ul className="ed-members">
                {g.employers.map((e) => {
                  const isTarget = e.id === g.suggestedTargetId;
                  return (
                    <li key={e.id}>
                      {canMerge && target && !isTarget ? (
                        <label>
                          <input
                            type="checkbox"
                            checked={!unticked.includes(e.id)}
                            onChange={() => toggle(e.id)}
                          />{' '}
                          {e.name}
                        </label>
                      ) : (
                        <strong>{e.name}</strong>
                      )}
                      <span className="ed-meta">
                        {plural(e.clients, 'client', 'clients')}
                        {isTarget ? ', keep this one' : ''}
                        {e.needsReview ? ', needs review' : ''}
                        {e.active ? '' : ', inactive'}
                      </span>
                    </li>
                  );
                })}
              </ul>

              {canMerge && !target && (
                <p className="ac-hint">
                  No employer in this group is active. Activate one with Edit first.
                </p>
              )}

              {canMerge && target && asking === key && (
                <>
                  <p className="alert" role="alert">
                    Merge {plural(others.length, 'employer', 'employers')} into {target.name}? This
                    cannot be undone.
                  </p>
                  <div className="ed-actions">
                    <button
                      type="button"
                      className="btn primary"
                      onClick={() => void merge(g)}
                      disabled={busy !== null}
                    >
                      {busy === key ? 'Merging...' : 'Yes, merge'}
                    </button>
                    <button type="button" className="btn" onClick={() => setAsking(null)}>
                      No
                    </button>
                  </div>
                </>
              )}

              {canMerge && target && asking !== key && (
                <div className="ed-actions">
                  <button
                    type="button"
                    className="btn primary"
                    onClick={() => setAsking(key)}
                    disabled={others.length === 0 || busy !== null}
                  >
                    {others.length === 0
                      ? 'Tick a name to merge'
                      : `Merge ${plural(others.length, 'employer', 'employers')} into ${target.name}`}
                  </button>
                </div>
              )}
            </li>
          );
        })}
      </ul>

      {groups.length > SHOWN && (
        <p>
          <button type="button" className="btn" onClick={() => setAll((v) => !v)}>
            {all ? 'Show fewer' : `Show all ${groups.length} groups`}
          </button>
        </p>
      )}
    </section>
  );
}
