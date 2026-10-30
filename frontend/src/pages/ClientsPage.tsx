import { useEffect, useState } from 'react';
import { data, type ClientSummary } from '../api';

export default function ClientsPage() {
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<ClientSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Wait 250 ms after typing stops, and ignore answers that arrive late.
  useEffect(() => {
    let current = true;
    setRows(null);
    const t = setTimeout(() => {
      data
        .listClients(query)
        .then((r) => {
          if (current) {
            setRows(r);
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

  return (
    <>
      <h1>Clients</h1>
      <div className="toolbar">
        <input
          type="search"
          placeholder="Search by name or ID ending"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search clients"
          autoComplete="off"
        />
      </div>

      {error && <p className="alert error" role="alert">{error}</p>}
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
                <th className="num">Open cases</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td>{c.fullName}</td>
                  <td className="mono">{c.idNumberMasked}</td>
                  <td>{c.consultantName ?? <span className="muted">Unassigned</span>}</td>
                  <td className="num">{c.openCases}</td>
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
