import { useEffect, useState, type FormEvent } from 'react';
import { catalogue, detailOf, isStale, type Employer, type PageResponse } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';

const SIZE = 25;

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

export default function EmployersPage() {
  const session = useSession();
  const isAdmin = hasAnyRole(session, ['ADMIN']);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [state, setState] = useState<'all' | 'active' | 'inactive'>('all');
  const [pageNo, setPageNo] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<PageResponse<Employer> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [panel, setPanel] = useState<{ employer: Employer | null } | null>(null);

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
        active: state === 'all' ? undefined : state === 'active',
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

  return (
    <>
      <div className="topbar-actions">
        <h1>Employers</h1>
        {isAdmin && (
          <button
            type="button"
            className="btn primary"
            onClick={() => setPanel({ employer: null })}
          >
            Add employer
          </button>
        )}
      </div>
      <p className="muted">
        From the Mafori Employer Schedule. Employers are deactivated, not deleted.
      </p>

      <div className="cn-filters">
        <div className="toolbar">
          <input
            type="search"
            placeholder="Search by name"
            aria-label="Search employers"
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
          <option value="all">All employers</option>
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
      {result && result.items.length === 0 && <p className="muted">No employers found.</p>}
      {result && result.items.length > 0 && (
        <>
          <div className="table-wrap">
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
                  <tr key={e.id}>
                    <td>{e.name}</td>
                    <td>{e.company ?? '-'}</td>
                    <td>{e.employerType ?? '-'}</td>
                    <td>
                      <span className={`badge ${e.active ? 'active' : 'suspended'}`}>
                        {e.active ? 'active' : 'inactive'}
                      </span>
                    </td>
                    <td>
                      {isAdmin && (
                        <button
                          type="button"
                          className="btn"
                          onClick={() => setPanel({ employer: e })}
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
    </>
  );
}
