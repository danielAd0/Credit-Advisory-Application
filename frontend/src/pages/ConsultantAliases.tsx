import { useEffect, useState, type FormEvent } from 'react';
import {
  accounts,
  consultantAliases,
  detailOf,
  type Consultant,
  type ConsultantAlias,
  type PageResponse,
} from '../api';
import { countOf } from '../components/countOf';
import Pager from '../components/Pager';
import StatusBadge from '../components/StatusBadge';
import './consultantaliases.css';

const SIZE = 50;

export default function ConsultantAliases() {
  const [people, setPeople] = useState<Consultant[]>([]);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [filterId, setFilterId] = useState('');
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<PageResponse<ConsultantAlias> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [addId, setAddId] = useState('');
  const [alias, setAlias] = useState('');
  const [adding, setAdding] = useState(false);
  const [asking, setAsking] = useState<number | null>(null);
  const [removing, setRemoving] = useState<number | null>(null);

  useEffect(() => {
    let live = true;
    accounts
      .listConsultants({ size: 100 })
      .then((r) => live && setPeople(r.items))
      .catch(() => live && setError('Could not load the consultants.'));
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(search.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  useEffect(() => {
    let live = true;
    consultantAliases
      .list({
        consultantId: filterId ? Number(filterId) : undefined,
        search: query,
        page,
        size: SIZE,
      })
      .then((r) => {
        if (!live) return;
        setResult(r);
        setError(null);
      })
      .catch((err) => live && setError(detailOf(err) ?? 'Could not load the name spellings.'));
    return () => {
      live = false;
    };
  }, [filterId, query, page, reload]);

  async function add(e: FormEvent) {
    e.preventDefault();
    if (adding) return;
    setNotice(null);
    setError(null);
    if (!addId) return setError('Choose the consultant this spelling belongs to.');
    if (!alias.trim()) return setError('Type the spelling as it appears in the workbook.');
    setAdding(true);
    try {
      const created = await consultantAliases.add(Number(addId), alias);
      setNotice(`"${created.alias}" now matches ${created.consultantName}.`);
      setAlias('');
      setPage(0);
      setReload((n) => n + 1);
    } catch (err) {
      setError(detailOf(err) ?? 'Could not add the spelling.');
    } finally {
      setAdding(false);
    }
  }

  async function remove(a: ConsultantAlias) {
    if (removing !== null) return;
    setRemoving(a.id);
    setNotice(null);
    setError(null);
    try {
      await consultantAliases.remove(a.id);
      setNotice(`"${a.alias}" was removed.`);
      setAsking(null);
    } catch (err) {
      setError(detailOf(err) ?? 'Could not remove the spelling.');
      setAsking(null);
    } finally {
      setRemoving(null);
      setReload((n) => n + 1);
    }
  }

  return (
    <section className="lp-card" aria-labelledby="ca-title">
      <div className="lp-head">
        <h2 id="ca-title">Name spellings</h2>
        {result && <span className="lp-count">{countOf(result.totalItems, 'spelling')}</span>}
        <div className="lp-filters ca-filters">
          <input
            type="search"
            placeholder="Search by spelling or name"
            aria-label="Search name spellings"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <select
            aria-label="Filter by consultant"
            value={filterId}
            onChange={(e) => {
              setFilterId(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All consultants</option>
            {people.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName}
              </option>
            ))}
          </select>
        </div>
      </div>
      <p className="ca-intro">
        The weekly workbook sometimes writes a consultant&apos;s name another way. A spelling saved
        here is matched to that consultant by later imports, so the import does not register a
        second consultant. Imports save new spellings here by themselves too.
      </p>

      <form className="ca-add" onSubmit={add} noValidate aria-label="Add a name spelling">
        <div className="ca-field">
          <label htmlFor="ca-consultant">Consultant</label>
          <select id="ca-consultant" value={addId} onChange={(e) => setAddId(e.target.value)}>
            <option value="">Choose a consultant</option>
            {people.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName} ({c.consultantCode})
              </option>
            ))}
          </select>
        </div>
        <div className="ca-field">
          <label htmlFor="ca-alias">Spelling in the workbook</label>
          <input
            id="ca-alias"
            value={alias}
            maxLength={200}
            onChange={(e) => setAlias(e.target.value)}
            placeholder="e.g. CENNET TSHISIMBA"
            autoComplete="off"
          />
        </div>
        <button type="submit" className="btn primary ca-submit" disabled={adding}>
          {adding ? 'Adding...' : 'Add spelling'}
        </button>
      </form>

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

      {!error && result === null && <p className="lp-empty">Loading...</p>}
      {result && result.items.length === 0 && (
        <p className="lp-empty">
          {query || filterId
            ? 'No spellings found.'
            : 'No spellings yet. Imports add them as they match new names.'}
        </p>
      )}
      {result && result.items.length > 0 && (
        <>
          <div className="lp-table">
            <table>
              <thead>
                <tr>
                  <th>Spelling</th>
                  <th>Consultant</th>
                  <th>Status</th>
                  <th className="num">Sales</th>
                  <th>Added</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((a) => (
                  <tr key={a.id}>
                    <td className="ca-spelling">{a.alias}</td>
                    <td>
                      {a.consultantName}
                      <span className="ca-code">{a.consultantCode}</span>
                    </td>
                    <td>
                      <StatusBadge status={a.consultantStatus} />
                    </td>
                    <td className="num">{a.transactions}</td>
                    <td className="ca-added">
                      {new Date(a.createdAt).toLocaleDateString('en-ZA')}
                      {a.createdBy && <span className="ca-by">by {a.createdBy}</span>}
                    </td>
                    <td>
                      {asking === a.id ? (
                        <span className="ca-ask">
                          Later imports will not match it.
                          <button
                            type="button"
                            className="btn primary lp-row-btn"
                            onClick={() => void remove(a)}
                            disabled={removing !== null}
                          >
                            {removing === a.id ? 'Removing...' : 'Yes, remove'}
                          </button>
                          <button
                            type="button"
                            className="btn lp-row-btn"
                            onClick={() => setAsking(null)}
                          >
                            No
                          </button>
                        </span>
                      ) : (
                        <button
                          type="button"
                          className="btn lp-row-btn"
                          onClick={() => setAsking(a.id)}
                        >
                          Remove
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
            onPage={setPage}
          />
        </>
      )}
    </section>
  );
}
