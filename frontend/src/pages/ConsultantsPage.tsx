import { useEffect, useState } from 'react';
import { data, type ConsultantSummary } from '../api';
import StatusBadge from '../components/StatusBadge';

export default function ConsultantsPage() {
  const [rows, setRows] = useState<ConsultantSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    data
      .listConsultants()
      .then(setRows)
      .catch(() => setError('Could not load consultants.'));
  }, []);

  return (
    <>
      <h1>Consultants</h1>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && rows === null && <p className="muted">Loading...</p>}
      {rows && rows.length === 0 && <p className="muted">No consultants yet.</p>}
      {rows && rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Code</th>
                <th>Name</th>
                <th>Status</th>
                <th>Since</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td className="mono">{c.consultantCode}</td>
                  <td>{c.fullName}</td>
                  <td>
                    <StatusBadge status={c.status} />
                  </td>
                  <td>{new Date(c.statusChangedAt).toLocaleDateString('en-ZA')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
