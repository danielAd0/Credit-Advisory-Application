import { NavLink, Outlet } from 'react-router-dom';
import type { Role } from '../api';
import { hasAnyRole, useAuth, useSession } from '../auth/useAuth';

const nav: { to: string; label: string; roles: Role[] }[] = [
  { to: '/', label: 'Dashboard', roles: ['ADMIN', 'MANAGER', 'CONSULTANT'] },
  { to: '/clients', label: 'Clients', roles: ['ADMIN', 'MANAGER', 'CONSULTANT'] },
  { to: '/consultants', label: 'Consultants', roles: ['ADMIN', 'MANAGER'] },
  { to: '/reassign', label: 'Reassign clients', roles: ['ADMIN', 'MANAGER'] },
  { to: '/import', label: 'Import', roles: ['ADMIN', 'MANAGER'] },
];

export default function Layout() {
  const session = useSession();
  const { signOut } = useAuth();
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <img className="brand-logo" src="/logo.png" alt="" />
          <span>Credit Advisory</span>
        </div>
        <nav aria-label="Main">
          {nav
            .filter((n) => hasAnyRole(session, n.roles))
            .map((n) => (
              <NavLink key={n.to} to={n.to} end={n.to === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
                {n.label}
              </NavLink>
            ))}
        </nav>
      </aside>

      <div className="main">
        <header className="topbar">
          <div className="who">
            <strong>{session.email}</strong>
            <span className="role">{session.roles.map((r) => r.toLowerCase()).join(', ')}</span>
          </div>
          <button className="btn ghost" onClick={() => void signOut()}>
            Sign out
          </button>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
