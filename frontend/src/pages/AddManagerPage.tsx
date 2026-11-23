import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { detailOf, users, type UserAccount } from '../api';
import { passwordProblem } from '../api/passwords';
import TempPassword from '../components/TempPassword';
import { accessLabel } from './managerAccess';
import './addclient.css';
import './consultants.css';

export default function AddManagerPage() {
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
    } catch (err) {
      setFailure(detailOf(err) ?? 'Could not create the manager. Try again.');
    } finally {
      setSaving(false);
    }
  }

  if (created) {
    return (
      <div className="ac">
        <h1>Manager added</h1>
        <div className="ac-card" role="status">
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
          <div className="ac-actions">
            <button type="button" className="btn primary" onClick={() => setCreated(null)}>
              Add another manager
            </button>
            <Link className="btn" to="/managers">
              Back to managers
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="ac">
      <h1>Add manager</h1>
      <p className="muted">Creates a sign-in for a manager. Consultants are added under Team.</p>
      <form className="ac-card" onSubmit={submit} noValidate>
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
        <div className="ac-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Create manager'}
          </button>
          <Link className="btn" to="/managers">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
