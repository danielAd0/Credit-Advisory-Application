import { useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { accounts, detailOf, type ConsultantAccount } from '../api';
import { passwordProblem } from '../api/passwords';
import TempPassword from '../components/TempPassword';
import './addclient.css';
import './consultants.css';

interface Form {
  consultantCode: string;
  fullName: string;
  email: string;
  temporaryPassword: string;
}

const EMPTY: Form = {
  consultantCode: '',
  fullName: '',
  email: '',
  temporaryPassword: '',
};
type Errors = Partial<Record<keyof Form, string>>;

function validate(v: Form): Errors {
  const e: Errors = {};
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/.test(v.consultantCode.trim()))
    e.consultantCode = 'Letters, digits, _ and - only, up to 32 characters, for example C012.';
  if (!v.fullName.trim()) e.fullName = 'Enter the consultant’s full name.';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email.trim()))
    e.email = 'That email address does not look right.';
  const bad = passwordProblem(v.temporaryPassword, v.email);
  if (bad) e.temporaryPassword = bad;
  return e;
}

export default function AddConsultantPage() {
  const [values, setValues] = useState<Form>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<{
    account: ConsultantAccount;
    password: string;
  } | null>(null);

  const set = (key: keyof Form) => (e: ChangeEvent<HTMLInputElement>) => {
    setValues((v) => ({ ...v, [key]: e.target.value }));
    setErrors((x) => ({ ...x, [key]: undefined }));
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (saving) return;
    setFailure(null);
    const found = validate(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      const account = await accounts.createConsultantAccount({
        consultantCode: values.consultantCode.trim().toUpperCase(),
        fullName: values.fullName.trim(),
        email: values.email.trim().toLowerCase(),
        temporaryPassword: values.temporaryPassword,
      });
      setCreated({ account, password: values.temporaryPassword });
      setValues(EMPTY);
    } catch (err) {
      setFailure(detailOf(err) ?? 'Could not create the consultant. Try again.');
    } finally {
      setSaving(false);
    }
  }

  if (created) {
    const { consultant, user } = created.account;
    return (
      <div className="ac">
        <h1>Consultant added</h1>
        <div className="ac-card" role="status">
          <p>
            <strong>{consultant.fullName}</strong> ({consultant.consultantCode}) can now sign in
            with <strong>{user.email}</strong>.
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
              Add another consultant
            </button>
            <Link className="btn" to="/consultants">
              Back to consultants
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const field = (key: Exclude<keyof Form, 'temporaryPassword'>, label: string, hint?: string) => (
    <div className="ac-field">
      <label htmlFor={`cn-${key}`}>{label}</label>
      <input
        id={`cn-${key}`}
        type={key === 'email' ? 'email' : 'text'}
        autoComplete="off"
        spellCheck={false}
        value={values[key]}
        onChange={set(key)}
        aria-invalid={errors[key] ? true : undefined}
      />
      {errors[key] ? (
        <p className="ac-err">{errors[key]}</p>
      ) : hint ? (
        <p className="ac-hint">{hint}</p>
      ) : null}
    </div>
  );

  return (
    <div className="ac">
      <h1>Add consultant</h1>
      <p className="muted">Creates the consultant and their sign-in together.</p>
      <form className="ac-card" onSubmit={submit} noValidate>
        {failure && (
          <p className="alert error" role="alert">
            {failure}
          </p>
        )}
        <div className="ac-row">
          {field('consultantCode', 'Consultant code', 'Never changes later. Saved in capitals.')}
          {field('fullName', 'Full name')}
        </div>
        {field('email', 'Email (used to sign in)')}
        <TempPassword
          id="cn-temporaryPassword"
          label="Temporary password"
          value={values.temporaryPassword}
          onChange={(v) => {
            setValues((x) => ({ ...x, temporaryPassword: v }));
            setErrors((x) => ({ ...x, temporaryPassword: undefined }));
          }}
          error={errors.temporaryPassword}
        />
        <div className="ac-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Create consultant'}
          </button>
          <Link className="btn" to="/consultants">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
