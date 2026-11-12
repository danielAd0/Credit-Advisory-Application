import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { audit, detailOf, type AuditEntry, type PageResponse } from '../api';
import ListPage from '../components/ListPage';
import { countOf } from '../components/countOf';
import { IconShield } from '../components/icons';
import Pager from '../components/Pager';
import './addclient.css';
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
  SALES_TARGET_SET: 'Sales target set',
  PRODUCT_UPDATED: 'Product updated',
  PRODUCT_QUOTATION_CREATED: 'Branch created',
  PRODUCT_QUOTATION_UPDATED: 'Branch renamed',
  PRODUCT_QUOTATION_ACTIVATED: 'Branch activated',
  PRODUCT_QUOTATION_DEACTIVATED: 'Branch deactivated',
  SALE_CAPTURED: 'Sale typed in',
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
  clientId: 'Client #',
  transactionDate: 'Date',
  products: 'Products',
  replacedTypedInSales: 'Typed-in sales replaced',
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
  'TRANSACTION',
];

/** Shown names that differ from the code: product quotations are called branches in the app. */
const TARGET_NAMES: Partial<Record<string, string>> = { PRODUCT_QUOTATION: 'Branch' };

/** IMPORT_JOB -> Import, PRODUCT_QUOTATION -> Branch. */
const targetLabel = (t: string) =>
  TARGET_NAMES[t] ??
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

/** Colour family of an action, for its chip in the "What" column. */
type Tone = 'alert' | 'security' | 'move' | 'ok' | 'info' | 'plain';

function toneOf(code: string): Tone {
  if (
    /FAILED$|_LOCKED$|RATE_LIMITED|LIMIT_REACHED|_DISABLED$|_DEACTIVATED$|_CANCELLED$|_EXPIRED$|_REMOVED$/.test(
      code,
    )
  )
    return 'alert';
  if (/PASSWORD|^MFA_|_UNLOCKED$|_ROLES_CHANGED$|^CLIENT_VIEWED$|ID_NUMBER/.test(code))
    return 'security';
  if (/TRANSFERRED|UNASSIGNED|REPLACED|MERGED|LINK_CHANGED/.test(code)) return 'move';
  if (/_CREATED$|_ASSIGNED$|_ENABLED$|_ACTIVATED$|_CONFIRMED$|_ADDED$/.test(code)) return 'ok';
  if (/^SALE_|EXPORTED|PREVIEWED|LOOKUP|_VIEWED$|TARGET_SET/.test(code)) return 'info';
  return 'plain';
}

/** ACTIVE -> Active */
const word = (v: unknown) =>
  typeof v === 'string' ? v.charAt(0) + v.slice(1).toLowerCase().replace(/_/g, ' ') : String(v);

/** Every labelled metadata field as "Label: value". */
function fields(e: AuditEntry, skip: string[] = []): string[] {
  if (!e.metadata) return [];
  return Object.entries(e.metadata).flatMap(([key, value]) => {
    if (skip.includes(key)) return [];
    const label = KEYS[key];
    const text = show(key, value);
    return label && text !== '' ? [`${label}: ${text}`] : [];
  });
}

/** The Details cell: a short headline, plus the reason on its own line when there is one. */
function summary(e: AuditEntry): { main: string; reason: string | null } {
  const m = e.metadata ?? {};
  const reason = typeof m.reason === 'string' && m.reason.trim() ? m.reason.trim() : null;
  const from = m.fromStatus ?? m.from;
  const to = m.toStatus ?? m.to;
  let main: string;
  if (typeof from === 'string' && typeof to === 'string' && from && to) {
    main = `${word(from)} → ${word(to)}`;
  } else if (typeof m.count === 'number') {
    main = `${m.count} ${m.count === 1 ? 'client' : 'clients'}`;
  } else if (m.clientId !== undefined && m.products !== undefined) {
    const n = Array.isArray(m.products) ? m.products.length : Number(m.products);
    main = `Client #${String(m.clientId)} · ${n} ${n === 1 ? 'product' : 'products'}`;
  } else {
    main = fields(e, ['reason']).join(' · ');
  }
  return { main, reason };
}

const target = (e: AuditEntry) =>
  e.targetType ? `${targetLabel(e.targetType)}${e.targetId ? ` #${e.targetId}` : ''}` : '';

const startOf = (date: string, addDays = 0) => {
  if (!date) return undefined;
  const d = new Date(`${date}T00:00:00`);
  d.setDate(d.getDate() + addDays);
  return d.toISOString();
};

// ---------- time, shown in South African time ----------

const TZ = 'Africa/Johannesburg';
const DAY = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: TZ,
});
const TIME = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
  timeZone: TZ,
});
const whenDay = (iso: string) => DAY.format(new Date(iso));
const whenTime = (iso: string) => `${TIME.format(new Date(iso))} SAST`;

const Dash = () => (
  <span className="lp-none" aria-label="none">
    —
  </span>
);

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

  const filtered = Boolean(action || targetType || fromDate || toDate);
  const count = result ? countOf(result.totalItems, 'entry', 'entries') : null;

  return (
    <ListPage
      title="Audit log"
      section="Administration"
      description={
        <>
          A complete record of who did what, and when.
          <span className="al-lock">
            <IconShield />
            Entries cannot be edited or deleted
          </span>
        </>
      }
      footer="A permanent, read-only record of workspace activity"
      action={<span className="al-tz">All times in SAST (UTC+02:00)</span>}
    >
      <section className="lp-card al" aria-labelledby="lp-title">
        <div className="lp-head">
          <h2 id="lp-title">Activity</h2>
          {count && <span className="al-count">{count}</span>}
        </div>

        <div className="al-filters">
          <div className="al-field">
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
          <div className="al-field">
            <label htmlFor="al-type">About</label>
            <select
              id="al-type"
              value={targetType}
              onChange={(e) => reset(() => setTargetType(e.target.value))()}
            >
              <option value="">All records</option>
              {TARGETS.map((t) => (
                <option key={t} value={t}>
                  {targetLabel(t)}
                </option>
              ))}
            </select>
          </div>
          <div className="al-field">
            <label htmlFor="al-id">Record number</label>
            <input
              id="al-id"
              inputMode="numeric"
              value={targetId}
              disabled={!targetType}
              placeholder="e.g. 4"
              onChange={(e) => reset(() => setTargetId(e.target.value.replace(/\D/g, '')))()}
            />
          </div>
          <div className="al-field">
            <label htmlFor="al-from">From</label>
            <input
              id="al-from"
              type="date"
              value={fromDate}
              onChange={(e) => reset(() => setFromDate(e.target.value))()}
            />
          </div>
          <div className="al-field">
            <label htmlFor="al-to">To</label>
            <input
              id="al-to"
              type="date"
              value={toDate}
              onChange={(e) => reset(() => setToDate(e.target.value))()}
            />
          </div>
        </div>

        <div className="al-bar">
          <span>
            <strong>{filtered ? 'Filtered activity' : 'All activity'}</strong>
            {count && (
              <>
                <span className="al-dot" aria-hidden="true">
                  •
                </span>
                {count}
              </>
            )}
          </span>
          <span>Newest first</span>
        </div>

        {error && (
          <p className="alert error" role="alert">
            {error}
          </p>
        )}
        {!error && result === null && <p className="lp-empty">Loading...</p>}
        {result && result.items.length === 0 && <p className="lp-empty">No entries found.</p>}
        {result && result.items.length > 0 && (
          <>
            <div className="lp-table al-table">
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
                  {result.items.map((e) => {
                    const { main, reason } = summary(e);
                    return (
                      <tr key={e.id}>
                        <td className="al-when">
                          <span>{whenDay(e.occurredAt)}</span>
                          <small>{whenTime(e.occurredAt)}</small>
                        </td>
                        <td>{e.actor ? e.actor.email : <span className="lp-pill">System</span>}</td>
                        <td>
                          <span className={`al-chip ${toneOf(e.action)}`}>
                            {actionLabel(e.action)}
                          </span>
                        </td>
                        <td>{target(e) || <Dash />}</td>
                        <td className="al-details">
                          {main ? <span>{main}</span> : !reason && <Dash />}
                          {reason && <small>Reason: {reason}</small>}
                        </td>
                      </tr>
                    );
                  })}
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
      </section>
    </ListPage>
  );
}
