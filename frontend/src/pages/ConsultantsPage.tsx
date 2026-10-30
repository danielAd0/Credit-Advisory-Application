import { useEffect, useState } from 'react';
import { data, type ConsultantSummary } from '../api';
import StatusBadge from '../components/StatusBadge';

export default function ConsultantsPage() {
  const [rows, setRows] = useState<ConsultantSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    data.listConsultants().then(setRows).catch(() => setError('Could not load consultants.'));
  }, []);

  return (
    <>
      <h1>Consultants</h1>
      {error && <p className="alert error" role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading...</p>}
      {rows && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Username</th>
                <th>Role</th>
                <th>Status</th>
                <th className="num">Clients</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td>{c.fullName}</td>
                  <td className="mono">{c.username}</td>
                  <td>{c.role.toLowerCase()}</td>
                  <td><StatusBadge status={c.status} /></td>
                  <td className="num">{c.clientCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
