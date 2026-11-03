import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { data, detailOf } from '../api';
import { useAuth, useSession } from '../auth/useAuth';
import './changepassword.css';

const MIN_LENGTH = 12;
const MAX_BYTES = 72;

/** The rules from the contract, so most mistakes are caught before anything is sent. */
function problems(current: string, next: string, confirm: string, email: string): string[] {
  const found: string[] = [];
  if (next.length < MIN_LENGTH)
    found.push(`The new password needs at least ${MIN_LENGTH} characters.`);
  if (new TextEncoder().encode(next).length > MAX_BYTES)
    found.push('The new password is too long.');
  if (next && next.toLowerCase() === email.toLowerCase())
    found.push('The new password cannot be your email address.');
  if (next && next === current)
    found.push('The new password must be different from the current one.');
  if (confirm !== next) found.push('The two new passwords do not match.');
  return found;
}

export default function ChangePasswordPage() {
  const session = useSession();
  const { refreshSession, signOut } = useAuth();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const forced = session.mustChangePassword;

  useEffect(() => {
    document.title = 'Choose your password - Credit Advisory';
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    const found = problems(current, next, confirm, session.email);
    setErrors(found);
    if (found.length > 0 || !current) {
      if (!current) setErrors([...found, 'Enter your current password.']);
      return;
    }
    setBusy(true);
    try {
      await data.changePassword({ currentPassword: current, newPassword: next });
      await refreshSession();
      navigate('/', { replace: true });
    } catch (err) {
      setCurrent('');
      setNext('');
      setConfirm('');
      setErrors([
        detailOf(err) ??
          'Could not change the password. Check your current password and try again.',
      ]);
      setBusy(false);
    }
  }

  return (
    <main className="cp">
      <form className="cp-card" onSubmit={submit} noValidate>
        <img className="cp-logo" src="/logo.png" alt="" />
        <h1>{forced ? 'Choose your own password' : 'Change your password'}</h1>
        <p className="muted">
          {forced
            ? 'An administrator set your password. Choose your own before you continue.'
            : 'Choose a new password. Your other devices will be signed out.'}
        </p>

        {errors.length > 0 && (
          <div className="alert error" role="alert">
            <ul className="cp-errors">
              {errors.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          </div>
        )}

        <div className="cp-field">
          <label htmlFor="cp-current">Current password</label>
          <input
            id="cp-current"
            type={show ? 'text' : 'password'}
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </div>
        <div className="cp-field">
          <label htmlFor="cp-new">New password</label>
          <input
            id="cp-new"
            type={show ? 'text' : 'password'}
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            aria-describedby="cp-rules"
          />
          <p id="cp-rules" className="cp-hint">
            At least {MIN_LENGTH} characters. Not your email address. Different from the current
            password.
          </p>
        </div>
        <div className="cp-field">
          <label htmlFor="cp-confirm">Type the new password again</label>
          <input
            id="cp-confirm"
            type={show ? 'text' : 'password'}
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
        </div>

        <label className="cp-show">
          <input type="checkbox" checked={show} onChange={(e) => setShow(e.target.checked)} /> Show
          passwords
        </label>

        <div className="cp-actions">
          <button type="submit" className="btn primary" disabled={busy}>
            {busy ? 'Saving...' : 'Save password'}
          </button>
          {forced ? (
            <button type="button" className="btn" onClick={() => void signOut()} disabled={busy}>
              Sign out
            </button>
          ) : (
            <Link className="btn" to="/">
              Cancel
            </Link>
          )}
        </div>
      </form>
    </main>
  );
}
