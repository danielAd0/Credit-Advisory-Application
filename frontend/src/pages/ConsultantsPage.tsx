import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  accounts,
  detailOf,
  isStale,
  type Consultant,
  type ConsultantStatus,
  type PageResponse,
  type UserAccount,
} from '../api';
import { passwordProblem } from '../api/passwords';
import { hasAnyRole, useSession } from '../auth/useAuth';
import Pager from '../components/Pager';
import StatusBadge from '../components/StatusBadge';
import TempPassword from '../components/TempPassword';
import './consultants.css';

const SIZE = 25;
const STATUSES: ConsultantStatus[] = ['ACTIVE', 'INACTIVE', 'SUSPENDED'];

type Notice = { kind: 'ok' | 'error'; text: string } | null;

interface PanelProps {
  consultant: Consultant;
  isAdmin: boolean;
  onChanged: (c: Consultant) => void;
  onClose: () => void;
}

function ManagePanel({ consultant, isAdmin, onChanged, onClose }: PanelProps) {
  const [status, setStatus] = useState<ConsultantStatus>(consultant.status);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState(false);
  const [login, setLogin] = useState<UserAccount | null | undefined>(undefined);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [temp, setTemp] = useState('');
  const [tempError, setTempError] = useState<string | undefined>();
  const [handedOver, setHandedOver] = useState<string | null>(null);
  const id = consultant.id;

  useEffect(() => {
    if (!isAdmin) return;
    let current = true;
    accounts
      .findLogin(id)
      .then((u) => current && setLogin(u))
      .catch(() => current && setLoginError('Could not load the login.'));
    return () => {
      current = false;
    };
  }, [id, isAdmin]);

  async function saveStatus(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (status === consultant.status)
      return setNotice({ kind: 'error', text: 'Choose a different status.' });
    if (!reason.trim())
      return setNotice({
        kind: 'error',
        text: 'Give a reason. It is kept in the audit log.',
      });
    setBusy(true);
    setNotice(null);
    try {
      const updated = await accounts.changeConsultantStatus(
        id,
        status,
        reason.trim(),
        consultant.version,
      );
      onChanged(updated);
      setReason('');
      setNotice({
        kind: 'ok',
        text:
          status === 'ACTIVE'
            ? 'The consultant is active again.'
            : 'Status changed. The consultant was signed out.',
      });
    } catch (err) {
      setNotice({
        kind: 'error',
        text: isStale(err)
          ? 'Someone else changed this consultant. Close this panel, reopen it and try again.'
          : (detailOf(err) ?? 'Could not change the status.'),
      });
    } finally {
      setBusy(false);
    }
  }

  async function reset(e: FormEvent) {
    e.preventDefault();
    if (busy || !login) return;
    const bad = passwordProblem(temp, login.email);
    setTempError(bad ?? undefined);
    if (bad) return;
    setBusy(true);
    setNotice(null);
    try {
      const u = await accounts.resetPassword(login.id, temp);
      setLogin(u);
      setHandedOver(temp);
      setTemp('');
      setNotice({
        kind: 'ok',
        text: 'Password reset. The consultant was signed out and unlocked.',
      });
    } catch (err) {
      setNotice({
        kind: 'error',
        text: detailOf(err) ?? 'Could not reset the password.',
      });
    } finally {
      setBusy(false);
    }
  }

  async function unlock() {
    if (busy || !login) return;
    setBusy(true);
    setNotice(null);
    try {
      setLogin(await accounts.unlockUser(login.id));
      setNotice({ kind: 'ok', text: 'The account is unlocked.' });
    } catch (err) {
      setNotice({
        kind: 'error',
        text: detailOf(err) ?? 'Could not unlock the account.',
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="cn-panel" aria-label={`Manage ${consultant.fullName}`}>
      <h2>
        {consultant.fullName} <span className="mono muted">{consultant.consultantCode}</span>
      </h2>
      <p className="muted">
        <StatusBadge status={consultant.status} /> · {consultant.clientCount} client
        {consultant.clientCount === 1 ? '' : 's'}
      </p>

      {notice && (
        <p className={`alert ${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      )}

      {consultant.status !== 'ACTIVE' && consultant.clientCount > 0 && (
        <p className="alert error" role="status">
          {consultant.clientCount} client
          {consultant.clientCount === 1 ? ' is' : 's are'} still assigned to this consultant.{' '}
          <Link to="/reassign">Reassign them</Link>; nothing moves automatically.
        </p>
      )}

      {!isAdmin ? (
        <p className="muted">Only an administrator can change a consultant or their login.</p>
      ) : (
        <>
          <h3>Status</h3>
          <form onSubmit={saveStatus} noValidate>
            <div className="ac-field">
              <label htmlFor="cn-status">New status</label>
              <select
                id="cn-status"
                value={status}
                onChange={(e) => setStatus(e.target.value as ConsultantStatus)}
              >
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {s.charAt(0) + s.slice(1).toLowerCase()}
                  </option>
                ))}
              </select>
            </div>
            <div className="ac-field">
              <label htmlFor="cn-reason">Reason</label>
              <textarea
                id="cn-reason"
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <p className="ac-hint">Required. Inactive or suspended consultants are signed out.</p>
            </div>
            <button type="submit" className="btn primary" disabled={busy}>
              Save status
            </button>
          </form>

          <h3>Login</h3>
          {loginError && <p className="alert error">{loginError}</p>}
          {!loginError && login === undefined && <p className="muted">Loading...</p>}
          {login === null && <p className="muted">This consultant has no login.</p>}
          {login && (
            <>
              <dl className="cn-facts">
                <dt>Email</dt>
                <dd>{login.email}</dd>
                <dt>Last sign-in</dt>
                <dd>
                  {login.lastLoginAt
                    ? new Date(login.lastLoginAt).toLocaleString('en-ZA')
                    : 'Never'}
                </dd>
                <dt>Account</dt>
                <dd>
                  {login.locked ? 'Locked after failed sign-ins' : 'Not locked'}
                  {login.mustChangePassword && ' · must choose a new password'}
                </dd>
              </dl>
              {login.locked && (
                <button type="button" className="btn" onClick={unlock} disabled={busy}>
                  Unlock account
                </button>
              )}
              <form onSubmit={reset} noValidate style={{ marginTop: '1rem' }}>
                <TempPassword
                  id="cn-temp"
                  label="New temporary password"
                  value={temp}
                  onChange={(v) => {
                    setTemp(v);
                    setTempError(undefined);
                    setHandedOver(null);
                  }}
                  error={tempError}
                />
                <button type="submit" className="btn" disabled={busy}>
                  Reset password
                </button>
              </form>
              {handedOver && (
                <p>
                  Give this to {consultant.fullName} in person. It is not shown again:
                  <br />
                  <span className="cn-secret">{handedOver}</span>
                </p>
              )}
            </>
          )}
        </>
      )}
      <div className="cn-actions">
        <button type="button" className="btn" onClick={onClose}>
          Close
        </button>
      </div>
    </section>
  );
}

export default function ConsultantsPage() {
  const session = useSession();
  const isAdmin = hasAnyRole(session, ['ADMIN']);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<ConsultantStatus | ''>('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<Consultant> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  // Wait a moment after typing before asking the server.
  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(search.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  useEffect(() => {
    let current = true;
    accounts
      .listConsultants({
        search: query,
        status: status || undefined,
        page,
        size: SIZE,
      })
      .then((r) => {
        if (!current) return;
        setResult(r);
        setError(null);
      })
      .catch(() => current && setError('Could not load consultants.'));
    return () => {
      current = false;
    };
  }, [query, status, page]);

  const replace = useCallback((c: Consultant) => {
    setResult((r) => r && { ...r, items: r.items.map((x) => (x.id === c.id ? c : x)) });
  }, []);

  const selected = result?.items.find((c) => c.id === selectedId) ?? null;

  return (
    <>
      <div className="topbar-actions">
        <h1>Consultants</h1>
        {isAdmin && (
          <Link className="btn primary" to="/consultants/new">
            Add consultant
          </Link>
        )}
      </div>

      <div className="cn-filters">
        <div className="toolbar">
          <input
            type="search"
            placeholder="Search by name or code"
            aria-label="Search consultants"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <select
          aria-label="Filter by status"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as ConsultantStatus | '');
            setPage(0);
          }}
        >
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.charAt(0) + s.slice(1).toLowerCase()}
            </option>
          ))}
        </select>
      </div>

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && <p className="muted">No consultants found.</p>}
      {result && result.items.length > 0 && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Status</th>
                  <th>Clients</th>
                  <th>Since</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((c) => (
                  <tr key={c.id}>
                    <td className="mono">{c.consultantCode}</td>
                    <td>{c.fullName}</td>
                    <td>
                      <StatusBadge status={c.status} />
                    </td>
                    <td>{c.clientCount}</td>
                    <td>{new Date(c.statusChangedAt).toLocaleDateString('en-ZA')}</td>
                    <td>
                      <button type="button" className="btn" onClick={() => setSelectedId(c.id)}>
                        {isAdmin ? 'Manage' : 'View'}
                      </button>
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

      {selected && (
        <ManagePanel
          key={selected.id}
          consultant={selected}
          isAdmin={isAdmin}
          onChanged={replace}
          onClose={() => setSelectedId(null)}
        />
      )}
    </>
  );
}
