import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  detailOf,
  isStale,
  mfa,
  users,
  type PageResponse,
  type Role,
  type UserAccount,
} from '../api';
import { passwordProblem } from '../api/passwords';
import { useSession } from '../auth/useAuth';
import Pager from '../components/Pager';
import TempPassword from '../components/TempPassword';
import './addclient.css';
import './consultants.css';

const SIZE = 20;

type Notice = { kind: 'ok' | 'error'; text: string } | null;

const isAdmin = (u: UserAccount) => u.roles.includes('ADMIN');
const isManager = (u: UserAccount) => u.roles.includes('MANAGER');

/** Made before the rule that a login is a manager or a consultant, never both. */
const isMixed = (u: UserAccount) =>
  isManager(u) && (u.roles.includes('CONSULTANT') || u.consultant !== null);

function accessLabel(u: UserAccount): string {
  return isAdmin(u) ? 'Manager + administrator' : 'Manager';
}

function StateBadge({ user }: { user: UserAccount }) {
  if (!user.enabled) return <span className="badge suspended">disabled</span>;
  if (user.locked) return <span className="badge suspended">locked</span>;
  return <span className="badge active">active</span>;
}

const signIn = (at: string | null) => (at ? new Date(at).toLocaleString('en-ZA') : 'Never');

/** Roles of a manager, with or without ADMIN. Any other roles they have are kept. */
function rolesWith(user: UserAccount, admin: boolean): Role[] {
  const rest = user.roles.filter((r) => r !== 'ADMIN' && r !== 'MANAGER');
  return admin ? [...rest, 'MANAGER', 'ADMIN'] : [...rest, 'MANAGER'];
}

// ---------- add ----------

function AddManager({ onCreated, onClose }: { onCreated: () => void; onClose: () => void }) {
  const [email, setEmail] = useState('');
  const [temp, setTemp] = useState('');
  const [alsoAdmin, setAlsoAdmin] = useState(false);
  const [errors, setErrors] = useState<{ email?: string; temp?: string }>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<{ user: UserAccount; password: string } | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (saving) return;
    const found: typeof errors = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim()))
      found.email = 'That email address does not look right.';
    const bad = passwordProblem(temp, email);
    if (bad) found.temp = bad;
    setErrors(found);
    setFailure(null);
    if (found.email || found.temp) return;
    setSaving(true);
    try {
      const user = await users.createUser({
        email: email.trim().toLowerCase(),
        temporaryPassword: temp,
        roles: alsoAdmin ? ['MANAGER', 'ADMIN'] : ['MANAGER'],
      });
      setCreated({ user, password: temp });
      setEmail('');
      setTemp('');
      setAlsoAdmin(false);
      onCreated();
    } catch (err) {
      setFailure(detailOf(err) ?? 'Could not create the manager. Try again.');
    } finally {
      setSaving(false);
    }
  }

  if (created) {
    return (
      <section className="cn-panel" role="status" aria-label="Manager added">
        <h2>Manager added</h2>
        <p>
          <strong>{created.user.email}</strong> can now sign in as{' '}
          {accessLabel(created.user).toLowerCase()}.
        </p>
        <p>
          Give them this temporary password in person. It is not shown again, and they must choose
          their own when they first sign in:
        </p>
        <p>
          <span className="cn-secret">{created.password}</span>
        </p>
        <div className="cn-actions">
          <button type="button" className="btn primary" onClick={() => setCreated(null)}>
            Add another manager
          </button>
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </section>
    );
  }

  return (
    <section className="cn-panel" aria-label="Add manager">
      <h2>Add manager</h2>
      <form onSubmit={submit} noValidate>
        {failure && (
          <p className="alert error" role="alert">
            {failure}
          </p>
        )}
        <div className="ac-field">
          <label htmlFor="mg-email">Email (used to sign in)</label>
          <input
            id="mg-email"
            type="email"
            autoComplete="off"
            spellCheck={false}
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
              setErrors((x) => ({ ...x, email: undefined }));
            }}
            aria-invalid={errors.email ? true : undefined}
          />
          {errors.email && <p className="ac-err">{errors.email}</p>}
        </div>
        <TempPassword
          id="mg-temp"
          label="Temporary password"
          recipient="manager"
          value={temp}
          onChange={(v) => {
            setTemp(v);
            setErrors((x) => ({ ...x, temp: undefined }));
          }}
          error={errors.temp}
        />
        <div className="ac-field">
          <label>
            <input
              type="checkbox"
              checked={alsoAdmin}
              onChange={(e) => setAlsoAdmin(e.target.checked)}
            />{' '}
            Also an administrator
          </label>
          <p className="ac-hint">
            Administrators can also manage users, consultants and the audit log.
          </p>
        </div>
        <div className="cn-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Create manager'}
          </button>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </section>
  );
}

// ---------- manage ----------

interface PanelProps {
  user: UserAccount;
  isSelf: boolean;
  onChanged: (u: UserAccount) => void;
  onClose: () => void;
}

function ManagePanel({ user, isSelf, onChanged, onClose }: PanelProps) {
  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState(false);
  const [admin, setAdmin] = useState(isAdmin(user));
  const [confirmDisable, setConfirmDisable] = useState(false);
  const [confirmMfaReset, setConfirmMfaReset] = useState(false);
  const [confirmFix, setConfirmFix] = useState<'manager' | 'consultant' | null>(null);
  const [temp, setTemp] = useState('');
  const [tempError, setTempError] = useState<string | undefined>();
  const [handedOver, setHandedOver] = useState<string | null>(null);

  async function run(action: () => Promise<UserAccount | void>, ok: string, failed: string) {
    if (busy) return false;
    setBusy(true);
    setNotice(null);
    try {
      const updated = await action();
      if (updated) onChanged(updated);
      setNotice({ kind: 'ok', text: ok });
      return true;
    } catch (err) {
      setNotice({
        kind: 'error',
        text: isStale(err)
          ? 'Someone else changed this user. Close this panel, reopen it and try again.'
          : (detailOf(err) ?? failed),
      });
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function saveAccess(e: FormEvent) {
    e.preventDefault();
    if (admin === isAdmin(user)) return setNotice({ kind: 'error', text: 'Nothing to change.' });
    await run(
      () => users.changeRoles(user.id, rolesWith(user, admin), user.version),
      isSelf
        ? 'Access changed. You are signed out and get the new access when you sign in again.'
        : 'Access changed. They were signed out and get the new access at their next sign-in.',
      'Could not change the access.',
    );
  }

  /** Drops the consultant side: the CONSULTANT role first, then the consultant link. */
  async function keepAsManager() {
    const done = await run(
      async () => {
        let u = user;
        if (u.roles.includes('CONSULTANT'))
          u = await users.changeRoles(
            u.id,
            u.roles.filter((r) => r !== 'CONSULTANT'),
            u.version,
          );
        if (u.consultant) u = await users.changeConsultant(u.id, null, u.version);
        return u;
      },
      'This login is now only a manager. They were signed out. If the consultant still works here, give them their own login under Team.',
      'Could not remove the consultant side.',
    );
    if (done) setConfirmFix(null);
  }

  /** Drops MANAGER; the login stays a consultant (and an administrator, if it was one). */
  async function keepAsConsultant() {
    const roles: Role[] = user.roles.filter((r) => r !== 'MANAGER');
    if (!roles.includes('CONSULTANT')) roles.push('CONSULTANT');
    const done = await run(
      () => users.changeRoles(user.id, roles, user.version),
      'This login is now only a consultant and was signed out. Give the manager a login with their own email.',
      'Could not remove the manager access.',
    );
    if (done) setConfirmFix(null);
  }

  async function setEnabled(enabled: boolean) {
    const done = await run(
      () => users.setEnabled(user.id, enabled, user.version),
      enabled ? 'The account is enabled again.' : 'The account is disabled and was signed out.',
      enabled ? 'Could not enable the account.' : 'Could not disable the account.',
    );
    if (done) setConfirmDisable(false);
  }

  async function reset(e: FormEvent) {
    e.preventDefault();
    const bad = passwordProblem(temp, user.email);
    setTempError(bad ?? undefined);
    if (bad) return;
    const password = temp;
    const done = await run(
      () => users.resetPassword(user.id, password),
      'Password reset. They were signed out and unlocked.',
      'Could not reset the password.',
    );
    if (done) {
      setHandedOver(password);
      setTemp('');
    }
  }

  async function resetMfa() {
    const done = await run(
      () => mfa.reset(user.id),
      'Two-step sign-in reset. They were signed out and set up a new authenticator app at their next sign-in.',
      'Could not reset two-step sign-in.',
    );
    if (done) setConfirmMfaReset(false);
  }

  return (
    <section className="cn-panel" aria-label={`Manage ${user.email}`}>
      <h2>
        {user.email} {isSelf && <span className="muted">(you)</span>}
      </h2>
      <p className="muted">
        <StateBadge user={user} /> · {accessLabel(user)}
      </p>

      {notice && (
        <p className={`alert ${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      )}

      {!isManager(user) ? (
        <p className="muted">
          This login is no longer a manager. Manage the consultant under Team.
        </p>
      ) : (
        <>
          <dl className="cn-facts">
            <dt>Last sign-in</dt>
            <dd>{signIn(user.lastLoginAt)}</dd>
            <dt>Account</dt>
            <dd>
              {user.locked ? 'Locked after failed sign-ins' : 'Not locked'}
              {user.mustChangePassword && ' · must choose a new password'}
            </dd>
            <dt>Created</dt>
            <dd>{new Date(user.createdAt).toLocaleDateString('en-ZA')}</dd>
          </dl>

          {isMixed(user) ? (
            <>
              <h3>Manager and consultant</h3>
              <p className="alert error" role="status">
                This login is both a manager and a consultant
                {user.consultant
                  ? ` (${user.consultant.fullName}, ${user.consultant.consultantCode})`
                  : ''}
                . That is no longer allowed: choose which one it stays. The other person needs a
                login with their own email.
              </p>
              {confirmFix ? (
                <div className="cn-actions">
                  <button
                    type="button"
                    className="btn primary"
                    disabled={busy}
                    onClick={() =>
                      void (confirmFix === 'manager' ? keepAsManager() : keepAsConsultant())
                    }
                  >
                    {confirmFix === 'manager'
                      ? 'Yes, keep only as manager'
                      : 'Yes, keep only as consultant'}
                  </button>
                  <button
                    type="button"
                    className="btn"
                    disabled={busy}
                    onClick={() => setConfirmFix(null)}
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                <div className="cn-actions">
                  <button
                    type="button"
                    className="btn"
                    disabled={busy}
                    onClick={() => setConfirmFix('manager')}
                  >
                    Keep as manager
                  </button>
                  <button
                    type="button"
                    className="btn"
                    disabled={busy}
                    onClick={() => setConfirmFix('consultant')}
                  >
                    Keep as consultant
                  </button>
                </div>
              )}
            </>
          ) : (
            <>
              <h3>Access</h3>
              <form onSubmit={saveAccess} noValidate>
                <div className="ac-field">
                  <label>
                    <input
                      type="checkbox"
                      checked={admin}
                      onChange={(e) => setAdmin(e.target.checked)}
                    />{' '}
                    Also an administrator
                  </label>
                  <p className="ac-hint">
                    Changing access signs the user out
                    {isSelf ? ', including you' : ''}. The last active administrator cannot lose it.
                  </p>
                </div>
                <button type="submit" className="btn" disabled={busy}>
                  Save access
                </button>
              </form>
            </>
          )}

          <h3>Account</h3>
          {user.locked && (
            <button
              type="button"
              className="btn"
              disabled={busy}
              onClick={() =>
                void run(
                  () => users.unlock(user.id),
                  'The account is unlocked.',
                  'Could not unlock.',
                )
              }
            >
              Unlock account
            </button>
          )}
          {user.enabled ? (
            isSelf ? (
              <p className="ac-hint">You cannot disable your own account.</p>
            ) : confirmDisable ? (
              <div className="cn-actions">
                <button
                  type="button"
                  className="btn primary"
                  onClick={() => void setEnabled(false)}
                  disabled={busy}
                >
                  Yes, disable this account
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => setConfirmDisable(false)}
                  disabled={busy}
                >
                  Cancel
                </button>
              </div>
            ) : (
              <div className="cn-actions">
                <button
                  type="button"
                  className="btn"
                  onClick={() => setConfirmDisable(true)}
                  disabled={busy}
                >
                  Disable account
                </button>
              </div>
            )
          ) : (
            <div className="cn-actions">
              <button
                type="button"
                className="btn primary"
                onClick={() => void setEnabled(true)}
                disabled={busy}
              >
                Enable account
              </button>
            </div>
          )}

          {!isSelf && (
            <>
              <h3>Password</h3>
              <form onSubmit={reset} noValidate>
                <TempPassword
                  id="mg-reset"
                  label="New temporary password"
                  recipient="manager"
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
                  Give this to them in person. It is not shown again:
                  <br />
                  <span className="cn-secret">{handedOver}</span>
                </p>
              )}

              <h3>Two-step sign-in</h3>
              <p className="ac-hint">
                Only when they lost their phone and their recovery codes. Check who you are talking
                to first: they are signed out now and set up a new authenticator app at their next
                sign-in.
              </p>
              {confirmMfaReset ? (
                <div className="cn-actions">
                  <button
                    type="button"
                    className="btn primary"
                    onClick={() => void resetMfa()}
                    disabled={busy}
                  >
                    Yes, reset two-step sign-in
                  </button>
                  <button
                    type="button"
                    className="btn"
                    onClick={() => setConfirmMfaReset(false)}
                    disabled={busy}
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  className="btn"
                  onClick={() => setConfirmMfaReset(true)}
                  disabled={busy}
                >
                  Reset two-step sign-in
                </button>
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

// ---------- page ----------

export default function ManagersPage() {
  const session = useSession();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<PageResponse<UserAccount> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(search.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  useEffect(() => {
    let current = true;
    users
      .listUsers({ search: query, role: 'MANAGER', page, size: SIZE })
      .then((r) => {
        if (!current) return;
        setResult(r);
        setError(null);
      })
      .catch(() => current && setError('Could not load managers.'));
    return () => {
      current = false;
    };
  }, [query, page, reload]);

  const replace = useCallback((u: UserAccount) => {
    setResult((r) => r && { ...r, items: r.items.map((x) => (x.id === u.id ? u : x)) });
  }, []);

  const selected = result?.items.find((u) => u.id === selectedId) ?? null;

  return (
    <>
      <div className="topbar-actions">
        <h1>Managers</h1>
        {!adding && (
          <button
            type="button"
            className="btn primary"
            onClick={() => {
              setAdding(true);
              setSelectedId(null);
            }}
          >
            Add manager
          </button>
        )}
      </div>
      <p className="muted">
        Sign-ins for managers and administrators. Consultants are managed under Team.
      </p>

      {adding && (
        <AddManager onCreated={() => setReload((n) => n + 1)} onClose={() => setAdding(false)} />
      )}

      <div className="cn-filters">
        <div className="toolbar">
          <input
            type="search"
            placeholder="Search by email"
            aria-label="Search managers"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && <p className="muted">No managers found.</p>}
      {result && result.items.length > 0 && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Email</th>
                  <th>Access</th>
                  <th>Status</th>
                  <th>Last sign-in</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.items.filter(isManager).map((u) => (
                  <tr key={u.id}>
                    <td>
                      {u.email}
                      {u.id === session.id && <span className="muted"> (you)</span>}
                    </td>
                    <td>{accessLabel(u)}</td>
                    <td>
                      <StateBadge user={u} />
                      {isMixed(u) && (
                        <>
                          {' '}
                          <span className="badge suspended">also a consultant</span>
                        </>
                      )}
                    </td>
                    <td>{signIn(u.lastLoginAt)}</td>
                    <td>
                      <button
                        type="button"
                        className="btn"
                        onClick={() => {
                          setSelectedId(u.id);
                          setAdding(false);
                        }}
                      >
                        Manage
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
          user={selected}
          isSelf={selected.id === session.id}
          onChanged={replace}
          onClose={() => setSelectedId(null)}
        />
      )}
    </>
  );
}
