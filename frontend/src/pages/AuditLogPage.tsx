import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { audit, detailOf, type AuditEntry, type PageResponse } from '../api';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';
import './audit.css';

const SIZE = 25;

const ACTIONS: Partial<Record<string, string>> = {
  CONSULTANT_CREATED: 'Consultant created',
  CONSULTANT_RENAMED: 'Consultant renamed',
  CONSULTANT_STATUS_CHANGED: 'Consultant status changed',
  CONSULTANT_ACCOUNT_CREATED: 'Consultant account created',
  USER_CREATED: 'User created',
  USER_ROLES_CHANGED: 'User roles changed',
  USER_CONSULTANT_LINK_CHANGED: 'User linked to a different consultant',
  USER_ENABLED: 'User enabled',
  USER_DISABLED: 'User disabled',
  USER_PASSWORD_RESET: 'Password reset by an administrator',
  USER_UNLOCKED: 'Account unlocked',
  PASSWORD_CHANGED: 'Password changed',
  ACCOUNT_LOCKED: 'Account locked after failed sign-ins',
  FIRST_ADMIN_CREATED: 'First administrator created',
  SIGN_IN_RATE_LIMITED: 'Sign-in attempts blocked',
  MFA_ENABLED: 'Two-step sign-in set up',
  MFA_RESET: 'Two-step sign-in reset',
  MFA_CODE_FAILED: 'Wrong two-step code',
  MFA_RECOVERY_CODE_USED: 'Recovery code used',
  MFA_RECOVERY_CODE_FAILED: 'Wrong recovery code',
  PASSWORD_CONFIRMATION_FAILED: 'Wrong password before showing an ID number',
  CLIENT_CREATED: 'Client created',
  CLIENT_VIEWED: 'Client ID number viewed',
  ID_REVEAL_LIMIT_REACHED: 'Too many ID numbers viewed',
  CLIENT_LOOKUP: 'Client looked up',
  CLIENT_UPDATED: 'Client updated',
  CLIENT_ID_NUMBER_CORRECTED: 'Client ID number corrected',
  CLIENTS_TRANSFERRED: 'Clients transferred',
  CLIENTS_ASSIGNED: 'Clients assigned',
  CLIENT_UNASSIGNED: 'Client unassigned',
  CASE_CREATED: 'Case created',
  CASE_VIEWED: 'Case viewed',
  CASE_UPDATED: 'Case updated',
  CASE_NOTE_ADDED: 'Case note added',
  IMPORT_PREVIEWED: 'Import previewed',
  IMPORT_CONFIRMED: 'Import confirmed',
  IMPORT_CANCELLED: 'Import cancelled',
  IMPORT_EXPIRED: 'Import preview expired',
  IMPORT_ISSUES_EXPORTED: 'Import problems exported',
  WEEK_REPLACED: 'Week replaced by a new import',
  CONSULTANT_ALIAS_CREATED: 'Consultant name alias added',
  CONSULTANT_ALIAS_REMOVED: 'Consultant name alias removed',
  REPORT_EXPORTED: 'Report exported',
  EMPLOYER_CREATED: 'Employer created',
  EMPLOYER_UPDATED: 'Employer updated',
  EMPLOYER_MERGED: 'Employers merged',
  EMPLOYER_REVIEWED: 'Employer reviewed',
  PRODUCT_CREATED: 'Product created',
  PRODUCT_UPDATED: 'Product updated',
  PRODUCT_QUOTATION_CREATED: 'Product quotation created',
  PRODUCT_QUOTATION_UPDATED: 'Product quotation renamed',
  PRODUCT_QUOTATION_ACTIVATED: 'Product quotation activated',
  PRODUCT_QUOTATION_DEACTIVATED: 'Product quotation deactivated',
};

const KEYS: Partial<Record<string, string>> = {
  consultantCode: 'Code',
  fullName: 'Name',
  name: 'Name',
  email: 'Email',
  roles: 'Roles',
  from: 'From',
  to: 'To',
  fromStatus: 'From',
  toStatus: 'To',
  reason: 'Reason',
  clientCount: 'Clients still assigned',
  count: 'Clients',
  fromConsultantId: 'From consultant #',
  toConsultantId: 'To consultant #',
  consultantId: 'Consultant #',
  fields: 'Changed',
  result: 'Result',
  lockedUntil: 'Locked until',
  address: 'Address',
  failures: 'Failures',
  locked: 'Account locked',
  hadMfa: 'Had two-step sign-in',
  remaining: 'Recovery codes left',
  recoveryCodes: 'Recovery codes',
};

const TARGETS = [
  'CLIENT',
  'USER',
  'CONSULTANT',
  'CASE',
  'IMPORT_JOB',
  'EMPLOYER',
  'PRODUCT',
  'PRODUCT_QUOTATION',
];

/** PRODUCT_QUOTATION -> Product quotation. */
const targetLabel = (t: string) =>
  (t.charAt(0) + t.slice(1).toLowerCase()).replace('_job', '').replace(/_/g, ' ');

function show(key: string, value: unknown): string {
  if (Array.isArray(value)) return value.join(', ');
  if (value === null || value === undefined) return 'none';
  if (key === 'lockedUntil' && typeof value === 'string')
    return new Date(value).toLocaleString('en-ZA');
  if (typeof value === 'object') return '';
  return String(value);
}

function actionLabel(code: string): string {
  return ACTIONS[code] ?? code;
}

function details(e: AuditEntry): string {
  if (!e.metadata) return '';
  return Object.entries(e.metadata)
    .flatMap(([key, value]) => {
      const label = KEYS[key];
      const text = show(key, value);
      return label && text !== '' ? [`${label}: ${text}`] : [];
    })
    .join(' · ');
}

const target = (e: AuditEntry) =>
  e.targetType ? `${targetLabel(e.targetType)}${e.targetId ? ` #${e.targetId}` : ''}` : '-';

const startOf = (date: string, addDays = 0) => {
  if (!date) return undefined;
  const d = new Date(`${date}T00:00:00`);
  d.setDate(d.getDate() + addDays);
  return d.toISOString();
};

export default function AuditLogPage() {
  const [params] = useSearchParams();
  const [action, setAction] = useState(params.get('action') ?? '');
  const [targetType, setTargetType] = useState(params.get('targetType') ?? '');
  const [targetId, setTargetId] = useState(params.get('targetId') ?? '');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<AuditEntry> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    document.title = 'Audit log - Credit Advisory';
  }, []);

  useEffect(() => {
    let live = true;
    audit
      .list({
        action: action || undefined,
        targetType: targetType || undefined,
        targetId: targetType && targetId.trim() ? targetId.trim() : undefined,
        from: startOf(fromDate),
        to: startOf(toDate, 1),
        page,
        size: SIZE,
      })
      .then((r) => {
        if (!live) return;
        setResult(r);
        setError(null);
      })
      .catch((err) => live && setError(detailOf(err) ?? 'Could not load the audit log.'));
    return () => {
      live = false;
    };
  }, [action, targetType, targetId, fromDate, toDate, page]);

  const reset = (fn: () => void) => () => {
    fn();
    setPage(0);
  };

  return (
    <>
      <h1>Audit log</h1>
      <p className="muted">Who did what, and when. Entries cannot be changed or deleted.</p>

      <div className="al-filters">
        <div className="ac-field">
          <label htmlFor="al-action">Action</label>
          <select
            id="al-action"
            value={action}
            onChange={(e) => reset(() => setAction(e.target.value))()}
          >
            <option value="">All actions</option>
            {Object.keys(ACTIONS).map((code) => (
              <option key={code} value={code}>
                {actionLabel(code)}
              </option>
            ))}
          </select>
        </div>
        <div className="ac-field">
          <label htmlFor="al-type">About</label>
          <select
            id="al-type"
            value={targetType}
            onChange={(e) => reset(() => setTargetType(e.target.value))()}
          >
            <option value="">Anything</option>
            {TARGETS.map((t) => (
              <option key={t} value={t}>
                {targetLabel(t)}
              </option>
            ))}
          </select>
        </div>
        <div className="ac-field">
          <label htmlFor="al-id">Number</label>
          <input
            id="al-id"
            inputMode="numeric"
            value={targetId}
            disabled={!targetType}
            placeholder="e.g. 4"
            onChange={(e) => reset(() => setTargetId(e.target.value.replace(/\D/g, '')))()}
          />
        </div>
        <div className="ac-field">
          <label htmlFor="al-from">From</label>
          <input
            id="al-from"
            type="date"
            value={fromDate}
            onChange={(e) => reset(() => setFromDate(e.target.value))()}
          />
        </div>
        <div className="ac-field">
          <label htmlFor="al-to">To</label>
          <input
            id="al-to"
            type="date"
            value={toDate}
            onChange={(e) => reset(() => setToDate(e.target.value))()}
          />
        </div>
      </div>

      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && <p className="muted">No entries found.</p>}
      {result && result.items.length > 0 && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Who</th>
                  <th>What</th>
                  <th>About</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((e) => (
                  <tr key={e.id}>
                    <td className="al-when">{new Date(e.occurredAt).toLocaleString('en-ZA')}</td>
                    <td>{e.actor ? e.actor.email : <span className="muted">System</span>}</td>
                    <td>{actionLabel(e.action)}</td>
                    <td>{target(e)}</td>
                    <td className="al-details">{details(e)}</td>
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
    </>
  );
}
