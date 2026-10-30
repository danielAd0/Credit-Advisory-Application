import { useEffect, useState } from 'react';
import { data, type ClientSummary } from '../api';

export default function ClientsPage() {
  const [query, setQuery] = useState('');
  // Remember which search the rows belong to, so "loading" can be worked out
  // instead of being set inside the effect.
  const [result, setResult] = useState<{ query: string; rows: ClientSummary[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Wait 250 ms after typing stops, and ignore answers that arrive late.
  useEffect(() => {
    let current = true;
    const t = setTimeout(() => {
      data
        .listClients(query)
        .then((rows) => {
          if (current) {
            setResult({ query, rows });
            setError(null);
          }
        })
        .catch(() => {
          if (current) setError('Could not load clients.');
        });
    }, 250);
    return () => {
      current = false;
      clearTimeout(t);
    };
  }, [query]);

  // null while the results for the current search have not arrived yet
  const rows = result && result.query === query ? result.rows : null;

  return (
    <>
      <h1>Clients</h1>
      <div className="toolbar">
        <input
          type="search"
          placeholder="Search by name"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search clients"
          autoComplete="off"
        />
      </div>

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && rows === null && <p className="muted">Loading...</p>}
      {rows && rows.length === 0 && <p className="muted">No clients match your search.</p>}

      {rows && rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>ID number</th>
                <th>Consultant</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td>{`${c.firstName} ${c.lastName}`}</td>
                  <td className="mono">{c.idNumberMasked}</td>
                  <td>{c.consultant?.fullName ?? <span className="muted">Unassigned</span>}</td>
                  <td>{new Date(c.updatedAt).toLocaleDateString('en-ZA')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
