import { useEffect, useState, type FormEvent } from 'react';
import { catalogue, detailOf, isStale, type PageResponse, type ProductQuotation } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';

const SIZE = 25;

interface PanelProps {
  quotation: ProductQuotation | null;
  /** The panel was just reopened on a new entry: say it was added. */
  justAdded: boolean;
  onSaved: (q: ProductQuotation, isNew: boolean) => void;
  onClose: () => void;
}

function QuotationPanel({ quotation, justAdded, onSaved, onClose }: PanelProps) {
  const [name, setName] = useState(quotation?.name ?? '');
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(justAdded ? 'Added.' : null);
  const [busy, setBusy] = useState(false);
  const [current, setCurrent] = useState<ProductQuotation | null>(quotation);

  const staleText = 'Someone else changed this branch. Close this panel, reopen it and try again.';

  async function save(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    setOk(null);
    if (!name.trim()) return setError('Enter the branch name.');
    setBusy(true);
    try {
      const saved = current
        ? await catalogue.updateProductQuotation(current.id, {
            name: name.trim(),
            version: current.version,
          })
        : await catalogue.createProductQuotation({ name: name.trim() });
      onSaved(saved, !current);
      setCurrent(saved);
      setName(saved.name);
      setOk('Saved.');
    } catch (err) {
      setError(isStale(err) ? staleText : (detailOf(err) ?? 'Could not save the branch.'));
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
      const saved = await catalogue.setProductQuotationActive(
        current.id,
        !current.active,
        current.version,
      );
      onSaved(saved, false);
      setCurrent(saved);
      setOk(
        saved.active
          ? 'The branch is active.'
          : 'The branch is deactivated. Clients that have it keep it, but it can no longer be chosen.',
      );
    } catch (err) {
      setError(isStale(err) ? staleText : (detailOf(err) ?? 'Could not change the branch.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="cn-panel" aria-label={current ? `Edit ${current.name}` : 'Add branch'}>
      <h2>{current ? `Edit ${current.name}` : 'Add branch'}</h2>
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
          <label htmlFor="pq-name">Name</label>
          <input
            id="pq-name"
            value={name}
            maxLength={100}
            onChange={(e) => setName(e.target.value)}
            autoComplete="off"
          />
          <p className="ac-hint">
            Names must be unique, ignoring capitals and extra spaces. A rename shows on every client
            that has it.
          </p>
        </div>
        <div className="cn-actions">
          <button type="submit" className="btn primary" disabled={busy}>
            {current ? 'Save changes' : 'Add branch'}
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

/** The branch list (C_BRANCHNAME in the old spreadsheets). Admins manage it. */
export default function ProductQuotationsPage() {
  const session = useSession();
  const isAdmin = hasAnyRole(session, ['ADMIN']);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [state, setState] = useState<'all' | 'active' | 'inactive'>('all');
  const [pageNo, setPageNo] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<PageResponse<ProductQuotation> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [panel, setPanel] = useState<{
    quotation: ProductQuotation | null;
    justAdded: boolean;
  } | null>(null);

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
      .listProductQuotations({
        search: query,
        active: state === 'all' ? undefined : state === 'active',
        page: pageNo,
        size: SIZE,
      })
      .then((r) => {
        if (!live) return;
        setResult(r);
        setError(null);
      })
      .catch(() => live && setError('Could not load branches.'));
    return () => {
      live = false;
    };
  }, [query, state, pageNo, reload]);

  return (
    <>
      <div className="topbar-actions">
        <h1>Branches</h1>
        {isAdmin && (
          <button
            type="button"
            className="btn primary"
            onClick={() => setPanel({ quotation: null, justAdded: false })}
          >
            Add branch
          </button>
        )}
      </div>
      <p className="muted">
        The list consultants choose from on a client. Branches are deactivated, not deleted.
      </p>

      <div className="cn-filters">
        <div className="toolbar">
          <input
            type="search"
            placeholder="Search by name"
            aria-label="Search branches"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <select
          aria-label="Filter by state"
          value={state}
          onChange={(e) => {
            setState(e.target.value as 'all' | 'active' | 'inactive');
            setPageNo(0);
          }}
        >
          <option value="all">All branches</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>
      </div>

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && (
        <p className="muted">
          {query || state !== 'all' ? 'No branches found.' : 'No branches yet.'}
          {isAdmin && !query && state === 'all' && ' Add the first one with the button above.'}
        </p>
      )}
      {result && result.items.length > 0 && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Status</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((q) => (
                  <tr key={q.id}>
                    <td>{q.name}</td>
                    <td>
                      <span className={`badge ${q.active ? 'active' : 'suspended'}`}>
                        {q.active ? 'active' : 'inactive'}
                      </span>
                    </td>
                    <td>
                      {isAdmin && (
                        <button
                          type="button"
                          className="btn"
                          onClick={() => setPanel({ quotation: q, justAdded: false })}
                        >
                          Edit
                        </button>
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

      {panel && (
        <QuotationPanel
          key={panel.quotation?.id ?? 'new'}
          quotation={panel.quotation}
          justAdded={panel.justAdded}
          onSaved={(saved, isNew) => {
            if (isNew) setPanel({ quotation: saved, justAdded: true });
            setReload((n) => n + 1);
          }}
          onClose={() => setPanel(null)}
        />
      )}
    </>
  );
}
