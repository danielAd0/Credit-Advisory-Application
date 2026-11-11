import { useEffect, useRef, useState, type ComponentType, type SVGProps } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { features, type Role, type Session } from '../api';
import { hasAnyRole, useAuth, useSession } from '../auth/useAuth';
import {
  IconBriefcase,
  IconChart,
  IconChevronDown,
  IconDownload,
  IconFile,
  IconFolder,
  IconGrid,
  IconQuote,
  IconSwap,
  IconTeam,
  IconUserCheck,
  IconUsers,
} from './icons';

interface NavItem {
  to: string;
  label: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  roles: Role[];
  enabled?: boolean;
}

const everyone: Role[] = ['ADMIN', 'MANAGER', 'CONSULTANT'];
const managers: Role[] = ['ADMIN', 'MANAGER'];

const groups: { label: string; items: NavItem[] }[] = [
  {
    label: 'Workspace',
    items: [
      { to: '/', label: 'Overview', icon: IconGrid, roles: everyone },
      { to: '/clients', label: 'Clients', icon: IconUsers, roles: everyone },
      {
        to: '/consultant-actions',
        label: 'Consultant actions',
        icon: IconUserCheck,
        roles: ['CONSULTANT'],
      },
      { to: '/sales-history', label: 'Sales history', icon: IconFile, roles: everyone },
      { to: '/reports', label: 'Reports', icon: IconChart, roles: everyone },
      { to: '/consultants', label: 'Team', icon: IconTeam, roles: managers },
      { to: '/products', label: 'Products', icon: IconFolder, roles: managers },
      { to: '/employers', label: 'Employers', icon: IconBriefcase, roles: managers },
      {
        to: '/product-quotations',
        label: 'Product quotations',
        icon: IconQuote,
        roles: managers,
      },
    ],
  },
  {
    label: 'Administration',
    items: [
      { to: '/managers', label: 'Managers', icon: IconUserCheck, roles: ['ADMIN'] },
      { to: '/audit-log', label: 'Audit log', icon: IconFile, roles: ['ADMIN'] },
      { to: '/reassign', label: 'Reassign clients', icon: IconSwap, roles: managers },
      {
        to: '/import',
        label: 'Import',
        icon: IconDownload,
        roles: managers,
        enabled: features.import,
      },
    ],
  },
];

/** Pages that are not in the sidebar but still need a breadcrumb. */
const extraCrumbs: { to: string; group: string; label: string }[] = [
  { to: '/charts', group: 'Workspace', label: 'Charts' },
];

function crumbsFor(pathname: string): { group: string; label: string } | null {
  const all = [
    ...groups.flatMap((g) => g.items.map((i) => ({ to: i.to, group: g.label, label: i.label }))),
    ...extraCrumbs,
  ];
  const match = all
    .filter((c) => (c.to === '/' ? pathname === '/' : pathname.startsWith(c.to)))
    .sort((a, b) => b.to.length - a.to.length)
    .at(0);
  return match ?? null;
}

/** "alex.morgan@x.co.za" -> "Alex Morgan"; used when the account has no consultant name. */
function nameFromEmail(email: string): string {
  const local = email.split('@')[0] ?? email;
  const words = local.split(/[._-]+/).filter(Boolean);
  if (words.length === 0) return email;
  return words.map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

function roleLabel(session: Session): string {
  if (session.roles.includes('ADMIN')) return 'Workspace admin';
  if (session.roles.includes('MANAGER')) return 'Manager';
  return 'Consultant';
}

function UserMenu() {
  const session = useSession();
  const { signOut } = useAuth();
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const name = session.consultant?.fullName ?? nameFromEmail(session.email);

  return (
    <div className="user-menu" ref={box}>
      {open && (
        <div className="user-menu-list" id="user-menu-list">
          <span className="sr-only">Signed in as {session.email}</span>
          <Link to="/change-password">Change password</Link>
          <button type="button" onClick={() => void signOut()}>
            Sign out
          </button>
        </div>
      )}
      <button
        type="button"
        aria-expanded={open}
        aria-controls="user-menu-list"
        title={session.email}
        onClick={() => setOpen((o) => !o)}
      >
        <span style={{ minWidth: 0 }}>
          <strong>{name}</strong>
          <small>{roleLabel(session)}</small>
        </span>
        <IconChevronDown />
      </button>
    </div>
  );
}

export default function Layout() {
  const session = useSession();
  const { pathname } = useLocation();
  const crumb = crumbsFor(pathname);

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <img className="brand-logo" src="/logo.png" alt="" />
          <span>Credit Advisory</span>
        </div>
        <nav aria-label="Main">
          {groups.map((g) => {
            const items = g.items.filter(
              (n) => n.enabled !== false && hasAnyRole(session, n.roles),
            );
            if (items.length === 0) return null;
            return (
              <div className="nav-group" key={g.label}>
                <p className="nav-label">{g.label}</p>
                <ul>
                  {items.map(({ to, label, icon: Icon }) => (
                    <li key={to}>
                      <NavLink
                        to={to}
                        end={to === '/'}
                        className={({ isActive }) => (isActive ? 'active' : '')}
                      >
                        <Icon />
                        {label}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </nav>
        <UserMenu />
      </aside>

      <div className="main">
        <header className="topbar">
          {crumb && (
            <nav className="crumbs" aria-label="Breadcrumb">
              <ol>
                <li>{crumb.group}</li>
                <li aria-current="page">{crumb.label}</li>
              </ol>
            </nav>
          )}
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
