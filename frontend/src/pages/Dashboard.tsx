import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { data } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';

export default function Dashboard() {
  const session = useSession();
  const [clients, setClients] = useState<number | null>(null);
  const [unassigned, setUnassigned] = useState<number | null>(null);

  useEffect(() => {
    data
      .listClients('')
      .then((list) => {
        setClients(list.length);
        setUnassigned(list.filter((c) => c.consultant === null).length);
      })
      .catch(() => {
        setClients(null);
      });
  }, []);

  const manager = hasAnyRole(session, ['MANAGER', 'ADMIN']);

  return (
    <>
      <h1>Dashboard</h1>
      <p className="muted">Signed in as {session.email}.</p>
      <div className="cards">
        <Link to="/clients" className="card">
          <span className="card-label">{manager ? 'Clients' : 'My clients'}</span>
          <span className="card-value">{clients ?? '-'}</span>
        </Link>
        {manager && (
          <Link to="/reassign" className="card">
            <span className="card-label">Waiting for a consultant</span>
            <span className="card-value">{unassigned ?? '-'}</span>
          </Link>
        )}
      </div>
    </>
  );
}
