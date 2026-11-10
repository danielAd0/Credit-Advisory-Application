import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, data, detailOf, type ClientSummary, type ConsultantSummary } from '../api';
import { clientName } from '../api/names';
import { hasAnyRole, useSession } from '../auth/useAuth';
import './addclient.css';

interface Form {
  firstName: string;
  lastName: string;
  idNumber: string;
  email: string;
  phone: string;
  consultantId: string;
}

const EMPTY: Form = {
  firstName: '',
  lastName: '',
  idNumber: '',
  email: '',
  phone: '',
  consultantId: '',
};

type Errors = Partial<Record<keyof Form, string>>;

function validate(v: Form): Errors {
  const e: Errors = {};
  if (!v.firstName.trim()) e.firstName = 'Enter the first name.';
  if (!v.lastName.trim()) e.lastName = 'Enter the last name.';
  if (!/^\d{13}$/.test(v.idNumber)) e.idNumber = 'The ID number must be exactly 13 digits.';
  if (v.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email))
    e.email = 'That email address does not look right.';
  if (v.phone && !/^\+?[\d\s]{9,15}$/.test(v.phone))
    e.phone = 'Use 9 to 15 digits, for example 082 000 0000.';
  return e;
}

function explain(err: unknown): string {
  const detail = detailOf(err);
  if (detail) return detail;
  if (err instanceof ApiError) {
    if (err.status === 409)
      return 'A client with that ID number already exists. Search for them on the Clients page instead.';
    if (err.status === 403) return 'You do not have permission to add clients.';
    if (err.status === 400)
      return 'The server rejected some of the details. Check that the ID number is a valid South African ID number and that the phone number is valid.';
    if (err.status === 422)
      return 'That consultant cannot take new clients right now. Choose another one.';
  }
  return 'Could not save the client. Try again.';
}

export default function AddClientPage() {
  const session = useSession();
  const isManager = hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const [values, setValues] = useState<Form>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [consultants, setConsultants] = useState<ConsultantSummary[]>([]);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [saved, setSaved] = useState<ClientSummary | null>(null);

  useEffect(() => {
    if (!isManager) return;
    let current = true;
    data
      .listConsultants()
      .then((r) => current && setConsultants(r.filter((c) => c.status === 'ACTIVE')))
      .catch(() => undefined);
    return () => {
      current = false;
    };
  }, [isManager]);

  const set = (key: keyof Form) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const raw = e.target.value;
    const value = key === 'idNumber' ? raw.replace(/\D/g, '').slice(0, 13) : raw;
    setValues((v) => ({ ...v, [key]: value }));
    setErrors((x) => ({ ...x, [key]: undefined }));
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    setFailure(null);
    const found = validate(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      const created = await data.createClient({
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        idNumber: values.idNumber,
        email: values.email.trim(),
        phone: values.phone.trim(),
        consultantId: isManager && values.consultantId ? Number(values.consultantId) : null,
      });
      setSaved(created);
      setValues(EMPTY);
    } catch (err) {
      setFailure(explain(err));
    } finally {
      setSaving(false);
    }
  }

  if (saved) {
    return (
      <div className="ac">
        <h1>Client added</h1>
        <div className="ac-card" role="status">
          <p>
            <strong>{clientName(saved)}</strong> was saved with ID number{' '}
            <span className="mono">{saved.idNumberMasked}</span>
            {saved.consultant ? (
              <> and assigned to {saved.consultant.fullName}</>
            ) : (
              <> and is not assigned to a consultant yet</>
            )}
            .
          </p>
          <div className="ac-actions">
            <button type="button" className="btn primary" onClick={() => setSaved(null)}>
              Add another client
            </button>
            <Link className="btn" to="/clients">
              Back to clients
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const field = (
    key: keyof Form,
    label: string,
    extra: {
      type?: string;
      inputMode?: 'numeric' | 'tel' | 'email';
      autoComplete?: string;
      hint?: string;
      required?: boolean;
    } = {},
  ) => (
    <div className="ac-field">
      <label htmlFor={`ac-${key}`}>
        {label}
        {extra.required && <span aria-hidden="true"> *</span>}
      </label>
      <input
        id={`ac-${key}`}
        type={extra.type ?? 'text'}
        inputMode={extra.inputMode}
        autoComplete={extra.autoComplete ?? 'off'}
        value={values[key]}
        onChange={set(key)}
        aria-invalid={errors[key] ? true : undefined}
        aria-describedby={errors[key] ? `ac-${key}-err` : extra.hint ? `ac-${key}-hint` : undefined}
        spellCheck={false}
      />
      {errors[key] ? (
        <p id={`ac-${key}-err`} className="ac-err">
          {errors[key]}
        </p>
      ) : extra.hint ? (
        <p id={`ac-${key}-hint`} className="ac-hint">
          {extra.hint}
        </p>
      ) : null}
    </div>
  );

  return (
    <div className="ac">
      <h1>Add client</h1>
      <p className="muted">
        Enter one new client by hand. To add many at once, use <Link to="/import">Import</Link>.
      </p>

      <form className="ac-card" onSubmit={submit} noValidate>
        {failure && (
          <p className="alert error" role="alert">
            {failure}
          </p>
        )}

        <div className="ac-row">
          {field('firstName', 'First name', { required: true })}
          {field('lastName', 'Last name', { required: true })}
        </div>
        {field('idNumber', 'ID number', {
          inputMode: 'numeric',
          hint: '13 digits. Shown masked everywhere after saving.',
          required: true,
        })}
        <div className="ac-row">
          {field('email', 'Email', { type: 'email', inputMode: 'email', autoComplete: 'off' })}
          {field('phone', 'Phone', { type: 'tel', inputMode: 'tel' })}
        </div>

        {isManager && (
          <div className="ac-field">
            <label htmlFor="ac-consultantId">Consultant</label>
            <select id="ac-consultantId" value={values.consultantId} onChange={set('consultantId')}>
              <option value="">Not assigned yet</option>
              {consultants.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.fullName}
                </option>
              ))}
            </select>
          </div>
        )}

        <div className="ac-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Save client'}
          </button>
          <Link className="btn" to="/clients">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
