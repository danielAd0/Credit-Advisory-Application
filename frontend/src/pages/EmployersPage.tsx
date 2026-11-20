import { useEffect, useState, type FormEvent } from 'react';
import {
  catalogue,
  detailOf,
  isStale,
  type Employer,
  type MergeEmployerResult,
  type PageResponse,
} from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import ListPage from '../components/ListPage';
import { countOf } from '../components/countOf';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';

const SIZE = 25;

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

interface Form {
  name: string;
  company: string;
  employerType: string;
}

const EMPTY: Form = { name: '', company: '', employerType: '' };

const toForm = (e: Employer): Form => ({
  name: e.name,
  company: e.company ?? '',
  employerType: e.employerType ?? '',
});

interface PanelProps {
  employer: Employer | null;
  onSaved: (e: Employer, isNew: boolean) => void;
  onClose: () => void;
}

function EmployerPanel({ employer, onSaved, onClose }: PanelProps) {
  const [v, setV] = useState<Form>(employer ? toForm(employer) : EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [current, setCurrent] = useState<Employer | null>(employer);

  const staleText =
    'Someone else changed this employer. Close this panel, reopen it and try again.';

  async function save(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    setOk(null);
    if (!v.name.trim()) return setError('Enter the employer name.');
    setBusy(true);
    try {
      const fields = {
        name: v.name.trim(),
        company: v.company.trim() || null,
        employerType: v.employerType.trim() || null,
      };
      const saved = current
        ? await catalogue.updateEmployer(current.id, { ...fields, version: current.version })
        : await catalogue.createEmployer(fields);
      onSaved(saved, !current);
      setCurrent(saved);
      setV(toForm(saved));
      setOk('Saved.');
    } catch (err) {
      setError(isStale(err) ? staleText : (detailOf(err) ?? 'Could not save the employer.'));
    } finally {
      setBusy(false);
    }
  }

  async function toggleActive() {
    if (busy || !current) return;
    setBusy(true);
    setError(null);
    setOk(null);
    try {
      const saved = await catalogue.setEmployerActive(current.id, !current.active, current.version);
      onSaved(saved, false);
      setCurrent(saved);
      setOk(saved.active ? 'The employer is active.' : 'The employer is deactivated.');
    } catch (err) {
      setError(isStale(err) ? staleText : (detailOf(err) ?? 'Could not change the employer.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="cn-panel" aria-label={current ? `Edit ${current.name}` : 'Add employer'}>
      <h2>{current ? `Edit ${current.name}` : 'Add employer'}</h2>
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
        <div className="ac-field">
          <label htmlFor="em-name">Employer name</label>
          <input
            id="em-name"
            value={v.name}
            onChange={(e) => setV({ ...v, name: e.target.value })}
            autoComplete="off"
          />
          <p className="ac-hint">Names must be unique, ignoring capitals and extra spaces.</p>
        </div>
        <div className="ac-row">
          <div className="ac-field">
            <label htmlFor="em-company">Company</label>
            <input
              id="em-company"
              value={v.company}
              onChange={(e) => setV({ ...v, company: e.target.value })}
              autoComplete="off"
            />
          </div>
          <div className="ac-field">
            <label htmlFor="em-type">Employer type</label>
            <input
              id="em-type"
              value={v.employerType}
              onChange={(e) => setV({ ...v, employerType: e.target.value })}
              autoComplete="off"
            />
          </div>
        </div>
        <div className="cn-actions">
          <button type="submit" className="btn primary" disabled={busy}>
            {current ? 'Save changes' : 'Add employer'}
          </button>
          {current && (
            <button type="button" className="btn" onClick={toggleActive} disabled={busy}>
              {current.active ? 'Deactivate' : 'Activate'}
            </button>
          )}
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </form>
    </section>
  );
}

interface MergePanelProps {
  employer: Employer;
  onMerged: () => void;
  onClose: () => void;
}

function MergePanel({ employer, onMerged, onClose }: MergePanelProps) {
  const [term, setTerm] = useState('');
  const [options, setOptions] = useState<Employer[]>([]);
  const [target, setTarget] = useState<Employer | null>(null);
  const [reason, setReason] = useState('');
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<MergeEmployerResult | null>(null);

  useEffect(() => {
    let live = true;
    const t = setTimeout(() => {
      catalogue
        .listEmployers({ search: term.trim(), active: true, size: 20 })
        .then((r) => live && setOptions(r.items.filter((x) => x.id !== employer.id)))
        .catch(() => live && setError('Could not load employers.'));
    }, 300);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [term, employer.id]);

  const choices =
    target && !options.some((o) => o.id === target.id) ? [target, ...options] : options;

  async function merge() {
    if (busy || !target) return;
    setBusy(true);
    setError(null);
    try {
      const result = await catalogue.mergeEmployer(employer.id, {
        targetEmployerId: target.id,
        version: employer.version,
        reason: reason.trim(),
      });
      setDone(result);
      onMerged();
    } catch (err) {
      setError(
        isStale(err)
          ? 'Someone else changed this employer. Close this panel, reopen it and try again.'
          : (detailOf(err) ?? 'Could not merge the employers.'),
      );
      setAsking(false);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="cn-panel" aria-label={`Merge ${employer.name}`}>
      <h2>Merge {employer.name}</h2>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {done ? (
        <>
          <p className="alert ok" role="status">
            Merged {done.mergedEmployerName} into {done.target.name}:{' '}
            {plural(done.clientsMoved, 'client', 'clients')} and{' '}
            {plural(done.transactionsMoved, 'sale', 'sales')} moved.
            {done.nameKeptAsAlias &&
              ` Its name is kept as a spelling of ${done.target.name}, so later imports match it there.`}
          </p>
          <div className="cn-actions">
            <button type="button" className="btn" onClick={onClose}>
              Close
            </button>
          </div>
        </>
      ) : (
        <>
          <p className="ac-hint">
            Use this when the import read the same employer under another spelling. Its clients and
            sales move to the employer you choose, and this one is removed.
          </p>
          <div className="ac-field">
            <label htmlFor="mg-search">Find the right employer</label>
            <input
              id="mg-search"
              type="search"
              value={term}
              onChange={(e) => setTerm(e.target.value)}
              autoComplete="off"
            />
          </div>
          <div className="ac-field">
            <label htmlFor="mg-target">Merge into</label>
            <select
              id="mg-target"
              value={target?.id ?? ''}
              onChange={(e) => {
                setTarget(choices.find((o) => o.id === Number(e.target.value)) ?? null);
                setAsking(false);
              }}
            >
              <option value="">Choose an employer</option>
              {choices.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}
                  {o.needsReview ? ' (needs review)' : ''}
                </option>
              ))}
            </select>
          </div>
          <div className="ac-field">
            <label htmlFor="mg-reason">Reason</label>
            <textarea
              id="mg-reason"
              rows={3}
              maxLength={500}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                setAsking(false);
              }}
            />
            <p className="ac-hint">Kept in the audit log.</p>
          </div>
          {asking && target ? (
            <>
              <p className="alert" role="alert">
                Merge {employer.name} into {target.name}? This cannot be undone.
              </p>
              <div className="cn-actions">
                <button type="button" className="btn primary" onClick={merge} disabled={busy}>
                  {busy ? 'Merging...' : 'Yes, merge'}
                </button>
                <button type="button" className="btn" onClick={() => setAsking(false)}>
                  No
                </button>
              </div>
            </>
          ) : (
            <div className="cn-actions">
              <button
                type="button"
                className="btn primary"
                onClick={() => setAsking(true)}
                disabled={!target || !reason.trim()}
              >
                Merge
              </button>
              <button type="button" className="btn" onClick={onClose}>
                Close
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}

export default function EmployersPage() {
  const session = useSession();
  const isAdmin = hasAnyRole(session, ['ADMIN']);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [state, setState] = useState<'all' | 'active' | 'inactive' | 'review'>('all');
  const [pageNo, setPageNo] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<PageResponse<Employer> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [panel, setPanel] = useState<{ employer: Employer | null } | null>(null);
  const [merge, setMerge] = useState<Employer | null>(null);
  const [reviewCount, setReviewCount] = useState(0);
  const [keeping, setKeeping] = useState<number | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(search.trim());
      setPageNo(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  useEffect(() => {
    let live = true;
    catalogue
      .listEmployers({
        search: query,
        active: state === 'active' ? true : state === 'inactive' ? false : undefined,
        needsReview: state === 'review' ? true : undefined,
        page: pageNo,
        size: SIZE,
      })
      .then((r) => {
        if (!live) return;
        setResult(r);
        setError(null);
      })
      .catch(() => live && setError('Could not load employers.'));
    return () => {
      live = false;
    };
  }, [query, state, pageNo, reload]);

  useEffect(() => {
    let live = true;
    catalogue
      .listEmployers({ needsReview: true, size: 1 })
      .then((r) => live && setReviewCount(r.totalItems))
      .catch(() => live && setReviewCount(0));
    return () => {
      live = false;
    };
  }, [reload]);

  async function keep(e: Employer) {
    if (keeping !== null) return;
    setKeeping(e.id);
    try {
      await catalogue.keepEmployer(e.id, e.version);
    } catch (err) {
      setError(
        isStale(err)
          ? 'Someone else changed this employer. The list has been reloaded: try again.'
          : (detailOf(err) ?? 'Could not keep the employer.'),
      );
    } finally {
      setKeeping(null);
      setReload((n) => n + 1);
    }
  }

  return (
    <ListPage
      title="Employers"
      description="From the Mafori Employer Schedule and the weekly imports. Employers are deactivated, not deleted."
      action={
        isAdmin && (
          <button
            type="button"
            className="btn primary"
            onClick={() => {
              setMerge(null);
              setPanel({ employer: null });
            }}
          >
            Add employer
          </button>
        )
      }
    >
      <section className="lp-card" aria-labelledby="lp-title">
        <div className="lp-head">
          <h2 id="lp-title">Employer schedule</h2>
          {result && <span className="lp-count">{countOf(result.totalItems, 'employer')}</span>}
          <div className="lp-filters">
            <input
              type="search"
              placeholder="Search by name"
              aria-label="Search employers"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <select
              aria-label="Filter by state"
              value={state}
              onChange={(e) => {
                setState(e.target.value as 'all' | 'active' | 'inactive' | 'review');
                setPageNo(0);
              }}
            >
              <option value="all">All employers</option>
              <option value="active">Active</option>
              <option value="inactive">Inactive</option>
              <option value="review">Needs review</option>
            </select>
          </div>
        </div>
        {reviewCount > 0 && state !== 'review' && (
          <p className="alert" role="status">
            {plural(reviewCount, 'employer was', 'employers were')} added by an import from a
            spelling nobody had seen.{' '}
            {isAdmin
              ? 'Merge each into the right employer, or keep it. '
              : 'An admin reviews them. '}
            <button type="button" className="btn lp-row-btn" onClick={() => setState('review')}>
              Show them
            </button>
          </p>
        )}
        {error && (
          <p className="alert error" role="alert">
            {error}
          </p>
        )}
        {!error && result === null && <p className="lp-empty">Loading...</p>}
        {result && result.items.length === 0 && (
          <p className="lp-empty">
            {state === 'review'
              ? 'No employers need review.'
              : !query && state === 'all'
                ? 'No employers yet. The first weekly import adds the ones in the sheet; add any others by hand.'
                : 'No employers found.'}
          </p>
        )}
        {result && result.items.length > 0 && (
          <>
            <div className="lp-table">
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Company</th>
                    <th>Type</th>
                    <th>Status</th>
                    <th>
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {result.items.map((e) => (
                    <tr
                      key={e.id}
                      className={
                        panel?.employer?.id === e.id || merge?.id === e.id
                          ? 'lp-selected'
                          : undefined
                      }
                    >
                      <td className="lp-strong">{e.name}</td>
                      <td>{e.company ?? <span className="lp-none">Not provided</span>}</td>
                      <td>{e.employerType ?? <span className="lp-none">Not provided</span>}</td>
                      <td>
                        <span className={`badge ${e.active ? 'active' : 'inactive'}`}>
                          {e.active ? 'active' : 'inactive'}
                        </span>
                        {e.needsReview && (
                          <>
                            {' '}
                            <span className="badge review">needs review</span>
                          </>
                        )}
                      </td>
                      <td>
                        {isAdmin && (
                          <>
                            <button
                              type="button"
                              className="btn lp-row-btn"
                              onClick={() => {
                                setMerge(null);
                                setPanel({ employer: e });
                              }}
                            >
                              Edit
                            </button>
                            {e.needsReview && (
                              <>
                                {' '}
                                <button
                                  type="button"
                                  className="btn lp-row-btn"
                                  onClick={() => {
                                    setPanel(null);
                                    setMerge(e);
                                  }}
                                >
                                  Merge into…
                                </button>{' '}
                                <button
                                  type="button"
                                  className="btn lp-row-btn"
                                  onClick={() => void keep(e)}
                                  disabled={keeping !== null}
                                >
                                  {keeping === e.id ? 'Keeping...' : 'Keep'}
                                </button>
                              </>
                            )}
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager
              page={result.page}
              size={result.size}
              totalItems={result.totalItems}
              totalPages={result.totalPages}
              onPage={setPageNo}
            />
          </>
        )}
      </section>

      {panel && (
        <EmployerPanel
          key={panel.employer?.id ?? 'new'}
          employer={panel.employer}
          onSaved={(saved, isNew) => {
            if (isNew) setPanel({ employer: saved });
            setReload((n) => n + 1);
          }}
          onClose={() => setPanel(null)}
        />
      )}

      {merge && (
        <MergePanel
          key={merge.id}
          employer={merge}
          onMerged={() => setReload((n) => n + 1)}
          onClose={() => setMerge(null)}
        />
      )}
    </ListPage>
  );
}
