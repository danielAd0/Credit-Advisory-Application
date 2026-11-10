import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError, data, detailOf, features, isStale, type ClientSummary } from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import ClientDetailsFields from '../components/ClientDetailsFields';
import {
  detailsFrom,
  detailsPayload,
  validateDetails,
  type DetailsValues,
} from '../components/clientDetails';
import './addclient.css';

interface Form extends DetailsValues {
  firstName: string;
  lastName: string;
  email: string;
  idNumber: string;
  idReason: string;
}

type Errors = Partial<Record<keyof Form, string>>;

function validate(v: Form): Errors {
  const e: Errors = validateDetails(v);
  if (!v.firstName.trim()) e.firstName = 'Enter the first name.';
  if (!v.lastName.trim()) e.lastName = 'Enter the last name.';
  if (v.idNumber && !/^\d{13}$/.test(v.idNumber))
    e.idNumber = 'The ID number must be exactly 13 digits.';
  if (v.idNumber && !v.idReason.trim()) e.idReason = 'Say why the ID number is being corrected.';
  if (v.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email))
    e.email = 'That email address does not look right.';
  return e;
}

export default function EditClientPage() {
  const { id: param = '' } = useParams();
  const id = Number(param);
  const navigate = useNavigate();
  const session = useSession();
  const canEditId = features.correctIdNumber && hasAnyRole(session, ['ADMIN', 'MANAGER']);
  const [loaded, setLoaded] = useState<{
    id: number;
    masked: string;
    version: number;
    /** For an employer or product quotation deactivated since it was chosen. */
    refs: Pick<ClientSummary, 'employer' | 'productQuotation'>;
  } | null>(null);
  const [loadState, setLoadState] = useState<'ok' | 'missing' | 'failed'>('ok');
  const [values, setValues] = useState<Form | null>(null);
  const [errors, setErrors] = useState<Errors>({});
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    if (!Number.isInteger(id)) return;
    let current = true;
    data
      .getClient(id)
      .then((c) => {
        if (!current) return;
        setLoaded({
          id,
          masked: c.idNumberMasked,
          version: c.version,
          refs: { employer: c.employer, productQuotation: c.productQuotation },
        });
        setValues({
          ...detailsFrom(c),
          firstName: c.firstName,
          lastName: c.lastName,
          email: c.email ?? '',
          idNumber: '',
          idReason: '',
        });
        setLoadState('ok');
      })
      .catch((err: unknown) => {
        if (current)
          setLoadState(err instanceof ApiError && err.status === 404 ? 'missing' : 'failed');
      });
    return () => {
      current = false;
    };
  }, [id]);

  const back = (
    <Link className="cd-back" to="/clients">
      &larr; Back to clients
    </Link>
  );

  if (!Number.isInteger(id) || loadState === 'missing') {
    return (
      <div className="ac">
        {back}
        <h1>Client not found</h1>
        <p className="muted">This client does not exist, or you do not have access to them.</p>
      </div>
    );
  }
  if (loadState === 'failed') {
    return (
      <div className="ac">
        {back}
        <p className="alert error" role="alert">
          Could not load this client. Refresh and try again.
        </p>
      </div>
    );
  }
  if (!loaded || loaded.id !== id || !values) {
    return (
      <div className="ac">
        {back}
        <p className="muted">Loading...</p>
      </div>
    );
  }

  const current = values;
  const change = (key: keyof Form, value: string) => {
    setValues({ ...current, [key]: value });
    setErrors((x) => ({ ...x, [key]: undefined }));
  };
  const set = (key: keyof Form) => (e: ChangeEvent<HTMLInputElement>) => {
    change(
      key,
      key === 'idNumber' ? e.target.value.replace(/\D/g, '').slice(0, 13) : e.target.value,
    );
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!loaded) return;
    setFailure(null);
    const found = validate(current);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      await data.updateClient(id, {
        ...detailsPayload(current),
        firstName: current.firstName.trim(),
        lastName: current.lastName.trim(),
        email: current.email.trim(),
        version: loaded.version,
        idNumber: canEditId && current.idNumber ? current.idNumber : undefined,
        idNumberReason: canEditId && current.idNumber ? current.idReason.trim() : undefined,
      });
      navigate(`/clients/${id}`);
    } catch (err) {
      setFailure(
        isStale(err)
          ? 'This client was changed by someone else. Go back, reopen the client and try again.'
          : (detailOf(err) ??
              (err instanceof ApiError && err.status === 403
                ? 'You do not have permission to edit this client.'
                : err instanceof ApiError && err.status === 404
                  ? 'This client no longer exists, or you do not have access to them.'
                  : err instanceof ApiError && err.status === 409
                    ? 'This client was changed by someone else, or that ID number belongs to another client. Go back, reopen the client and try again.'
                    : err instanceof ApiError && err.status === 400
                      ? 'The server rejected some of the details. Check the email and phone numbers.'
                      : err instanceof ApiError && err.status === 422
                        ? 'That employer or product quotation has been deactivated. Choose another one.'
                        : 'Could not save the changes. Nothing was changed. Try again.')),
      );
      setSaving(false);
    }
  }

  const field = (
    key: 'firstName' | 'lastName' | 'email' | 'idNumber' | 'idReason',
    label: string,
    type = 'text',
    required = false,
    hint?: string,
  ) => (
    <div className="ac-field">
      <label htmlFor={`ec-${key}`}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      <input
        id={`ec-${key}`}
        type={type}
        autoComplete="off"
        spellCheck={false}
        value={current[key]}
        onChange={set(key)}
        aria-invalid={errors[key] ? true : undefined}
        inputMode={key === 'idNumber' ? 'numeric' : undefined}
        aria-describedby={errors[key] ? `ec-${key}-err` : undefined}
      />
      {errors[key] ? (
        <p id={`ec-${key}-err`} className="ac-err">
          {errors[key]}
        </p>
      ) : hint ? (
        <p className="ac-hint">{hint}</p>
      ) : null}
    </div>
  );

  return (
    <div className="ac">
      {back}
      <h1>Edit client</h1>
      <p className="muted">
        ID number <span className="mono">{loaded.masked}</span>.{' '}
        {canEditId
          ? 'To correct it, type the full new number below. '
          : 'The ID number cannot be changed here. '}
        To move this client to another consultant, use Reassign clients.
      </p>

      <form className="ac-card" onSubmit={submit} noValidate>
        {failure && (
          <p className="alert error" role="alert">
            {failure}
          </p>
        )}
        <div className="ac-row">
          {field('firstName', 'First name', 'text', true)}
          {field('lastName', 'Last name', 'text', true)}
        </div>
        {canEditId &&
          field(
            'idNumber',
            'Correct ID number',
            'text',
            false,
            'Leave blank to keep the current ID number. The change is recorded with your name.',
          )}
        {canEditId && current.idNumber && field('idReason', 'Reason for the correction')}
        {field('email', 'Email', 'email')}
        <ClientDetailsFields
          idPrefix="ec"
          values={current}
          errors={errors}
          onChange={change}
          current={loaded.refs}
        />
        <div className="ac-actions">
          <button type="submit" className="btn primary" disabled={saving}>
            {saving ? 'Saving...' : 'Save changes'}
          </button>
          <Link className="btn" to="/clients">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}
