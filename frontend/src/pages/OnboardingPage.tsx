import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, REAUTHENTICATION_REQUIRED, detailOf, idReveal, type PageResponse } from '../api';
import {
  mockConfirmPassword,
  onboarding,
  type BlockedConsultant,
  type GenerateResult,
  type Invite,
  type InviteStatus,
  type OnboardingSummary,
} from '../api/onboarding';
import ListPage from '../components/ListPage';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';
import './imports.css';
import './onboarding.css';

const SIZE = 50;

const DESCRIPTION =
  'Consultants are registered from the CONSULTANT EMAIL column when a weekly register is imported. Here you make their logins and email each consultant their own details.';
const FOOTER = 'Confidential staff information';

type Notice = { kind: 'ok' | 'error'; text: string } | null;

const STATUS_LABEL: Record<InviteStatus, string> = {
  PENDING: 'Not sent yet',
  QUEUED: 'Waiting to send',
  SENDING: 'Sending',
  SENT: 'Sent',
  FAILED: 'Failed',
};

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString('en-ZA') : '-');

/** Asks for the admin's own password again before passwords are made or sent. */
function ConfirmPassword({
  onConfirmed,
  onCancel,
}: {
  onConfirmed: () => void;
  onCancel: () => void;
}) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || !password) return;
    setBusy(true);
    setError(null);
    try {
      await idReveal.confirmPassword(password);
      mockConfirmPassword();
      setPassword('');
      onConfirmed();
    } catch (err) {
      setError(detailOf(err) ?? 'Could not check the password.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="cn-panel" onSubmit={submit} noValidate aria-label="Confirm your password">
      <h3>Enter your password again</h3>
      <p className="muted">
        Making and sending consultant passwords needs your own password, entered in the last 5
        minutes.
      </p>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      <div className="ac-field">
        <label htmlFor="ob-password">Your password</label>
        <input
          id="ob-password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </div>
      <div className="cn-actions">
        <button type="submit" className="btn primary" disabled={busy || !password}>
          {busy ? 'Checking...' : 'Continue'}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function NeedsAttention({ onChanged }: { onChanged: () => void }) {
  const [page, setPage] = useState(0);
  const [list, setList] = useState<PageResponse<BlockedConsultant> | null>(null);
  const [email, setEmail] = useState<Partial<Record<number, string>>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      setList(await onboarding.blocked(page, SIZE));
    } catch {
      setError('Could not load the consultants that need attention.');
    }
  }, [page]);

  useEffect(() => {
    void load();
  }, [load]);

  async function save(id: number) {
    const value = email[id]?.trim();
    if (!value || busy !== null) return;
    setBusy(id);
    setError(null);
    try {
      await onboarding.setConsultantEmail(id, value);
      setEmail({ ...email, [id]: '' });
      await load();
      onChanged();
    } catch (err) {
      setError(detailOf(err) ?? 'Could not save the email.');
    } finally {
      setBusy(null);
    }
  }

  if (!list || list.totalItems === 0) return null;
  return (
    <section className="wi-card" aria-label="Consultants that need attention">
      <h2>Consultants who cannot get a login yet</h2>
      <p className="muted">
        Active consultants without a login who have no email, or whose email another login already
        uses. Add the email to the next weekly register, or fill it in here.
      </p>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Name</th>
              <th>Problem</th>
              <th>Email</th>
            </tr>
          </thead>
          <tbody>
            {list.items.map((c) => (
              <tr key={c.consultantId}>
                <td>{c.consultantCode}</td>
                <td>{c.fullName}</td>
                <td>
                  {c.reason === 'NO_EMAIL'
                    ? 'No email'
                    : `${c.email ?? ''} is used by another login`}
                </td>
                <td>
                  <div className="wi-map">
                    <input
                      type="email"
                      aria-label={`Email for ${c.fullName}`}
                      placeholder="name@mafori.co.za"
                      value={email[c.consultantId] ?? ''}
                      onChange={(e) => setEmail({ ...email, [c.consultantId]: e.target.value })}
                    />
                    <button
                      type="button"
                      className="btn"
                      disabled={!email[c.consultantId]?.trim() || busy !== null}
                      onClick={() => void save(c.consultantId)}
                    >
                      Save
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pager
        page={list.page}
        size={list.size}
        totalItems={list.totalItems}
        totalPages={list.totalPages}
        onPage={setPage}
      />
    </section>
  );
}

function DeliveryReport({
  summary,
  refreshKey,
  onRetry,
  busy,
}: {
  summary: OnboardingSummary;
  refreshKey: number;
  onRetry: () => void;
  busy: boolean;
}) {
  const [status, setStatus] = useState<InviteStatus | ''>('');
  const [page, setPage] = useState(0);
  const [list, setList] = useState<PageResponse<Invite> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    onboarding
      .invites(status, page, SIZE)
      .then((r) => live && setList(r))
      .catch(() => live && setError('Could not load the email report.'));
    return () => {
      live = false;
    };
  }, [status, page, refreshKey]);

  const total = Object.values(summary.invites).reduce((a, b) => a + b, 0);
  if (total === 0) return null;
  const filters: (InviteStatus | '')[] = ['', 'PENDING', 'QUEUED', 'SENT', 'FAILED'];

  return (
    <section className="wi-card" aria-label="Email delivery report">
      <h2>Email delivery report</h2>
      <div className="wi-stats">
        <div className="wi-stat">
          <strong>{summary.invites.SENT}</strong>
          <span>Sent</span>
        </div>
        <div className="wi-stat">
          <strong>{summary.invites.QUEUED + summary.invites.SENDING}</strong>
          <span>Waiting to send</span>
        </div>
        <div className="wi-stat">
          <strong>{summary.invites.PENDING}</strong>
          <span>Not sent yet</span>
        </div>
        <div className={`wi-stat ${summary.invites.FAILED > 0 ? 'bad' : ''}`}>
          <strong>{summary.invites.FAILED}</strong>
          <span>Failed</span>
        </div>
      </div>
      {summary.invites.FAILED > 0 && (
        <div className="wi-actions">
          <button type="button" className="btn" onClick={onRetry} disabled={busy}>
            Retry {summary.invites.FAILED} failed email{summary.invites.FAILED === 1 ? '' : 's'}
          </button>
          <span className="muted">The same temporary passwords are sent again.</span>
        </div>
      )}

      <div className="wi-tabs" aria-label="Filter by status">
        {filters.map((f) => (
          <button
            key={f || 'ALL'}
            type="button"
            className="btn"
            aria-pressed={status === f}
            onClick={() => {
              setStatus(f);
              setPage(0);
            }}
          >
            {f ? STATUS_LABEL[f] : 'All'}
          </button>
        ))}
      </div>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {list && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Consultant</th>
                  <th>Email</th>
                  <th>Status</th>
                  <th>Attempts</th>
                  <th>Sent</th>
                  <th>Problem</th>
                </tr>
              </thead>
              <tbody>
                {list.items.map((i) => (
                  <tr key={i.id}>
                    <td>
                      {i.consultantName} <span className="muted">({i.consultantCode})</span>
                    </td>
                    <td>{i.email}</td>
                    <td>
                      <span
                        className={`badge ${
                          i.status === 'SENT' ? 'active' : i.status === 'FAILED' ? 'suspended' : ''
                        }`}
                      >
                        {STATUS_LABEL[i.status]}
                      </span>
                    </td>
                    <td>{i.attempts}</td>
                    <td>{when(i.sentAt)}</td>
                    <td>
                      {i.lastError ?? <span className="muted">-</span>}
                      {i.status === 'QUEUED' && i.nextAttemptAt && i.attempts > 0 && (
                        <span className="muted"> Next try {when(i.nextAttemptAt)}.</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={list.page}
            size={list.size}
            totalItems={list.totalItems}
            totalPages={list.totalPages}
            onPage={setPage}
          />
        </>
      )}
    </section>
  );
}

export default function OnboardingPage() {
  const [summary, setSummary] = useState<OnboardingSummary | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [skipped, setSkipped] = useState<GenerateResult['skipped']>([]);
  const [askPassword, setAskPassword] = useState<null | (() => Promise<void>)>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const reload = useCallback(async () => {
    try {
      setSummary(await onboarding.summary());
      setRefreshKey((k) => k + 1);
    } catch {
      setLoadError('Could not load consultant logins.');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  // While emails are going out, refresh every 5 seconds.
  const sending = summary ? summary.invites.QUEUED + summary.invites.SENDING > 0 : false;
  useEffect(() => {
    if (!sending) return;
    const t = setInterval(() => void reload(), 5000);
    return () => clearInterval(t);
  }, [sending, reload]);

  /** Runs an action; if the server wants the password first, asks for it and runs it again. */
  async function withPassword(action: () => Promise<void>) {
    setNotice(null);
    try {
      await action();
    } catch (err) {
      if (err instanceof ApiError && err.type === REAUTHENTICATION_REQUIRED) {
        setAskPassword(() => action);
        return;
      }
      setNotice({
        kind: 'error',
        text: detailOf(err) ?? 'Something went wrong. Please try again.',
      });
    }
  }

  async function generateAll() {
    setBusy(true);
    setSkipped([]);
    let made = 0;
    const left: GenerateResult['skipped'] = [];
    try {
      // 25 per call; keep going while the server makes some and some remain.
      for (;;) {
        const r = await onboarding.generate();
        made += r.created;
        left.push(...r.skipped.filter((s) => !left.some((x) => x.consultantId === s.consultantId)));
        setProgress(`${made} login${made === 1 ? '' : 's'} made, ${r.remaining} to go...`);
        if (r.remaining === 0 || r.created === 0) break;
      }
      setSkipped(left);
      setNotice({
        kind: 'ok',
        text: `${made} temporary password${made === 1 ? ' was' : 's were'} made. Nothing has been emailed yet: press Send login details.`,
      });
    } finally {
      setProgress(null);
      setBusy(false);
      await reload();
    }
  }

  async function sendAll() {
    setBusy(true);
    try {
      const r = await onboarding.send();
      setNotice({
        kind: 'ok',
        text: `${r.queued} email${r.queued === 1 ? ' is' : 's are'} being sent in the background, about 20 a minute. You can leave this page.`,
      });
    } finally {
      setBusy(false);
      await reload();
    }
  }

  async function retryFailed() {
    setBusy(true);
    try {
      const r = await onboarding.retry();
      setNotice({
        kind: 'ok',
        text: `${r.queued} failed email${r.queued === 1 ? ' is' : 's are'} being sent again.`,
      });
    } finally {
      setBusy(false);
      await reload();
    }
  }

  if (loadError)
    return (
      <ListPage title="Consultant logins" description={DESCRIPTION} footer={FOOTER}>
        <p className="alert error ob-top">{loadError}</p>
      </ListPage>
    );
  if (!summary)
    return (
      <ListPage title="Consultant logins" description={DESCRIPTION} footer={FOOTER}>
        <p className="lp-empty ob-top">Loading...</p>
      </ListPage>
    );

  return (
    <ListPage title="Consultant logins" description={DESCRIPTION} footer={FOOTER}>
      <div className="wi ob">
        <div className="wi-stats ob-stats">
          <div className="wi-stat">
            <strong>{summary.activeConsultants}</strong>
            <span>Active consultants</span>
          </div>
          <div className="wi-stat">
            <strong>{summary.withEmail}</strong>
            <span>With an email</span>
          </div>
          <div className="wi-stat">
            <strong>{summary.withLogin}</strong>
            <span>With a login</span>
          </div>
          <div className="wi-stat">
            <strong>{summary.eligibleForLogin}</strong>
            <span>Ready for a login</span>
          </div>
          <div className={`wi-stat ${summary.needAttention > 0 ? 'bad' : ''}`}>
            <strong>{summary.needAttention}</strong>
            <span>Need attention</span>
          </div>
        </div>

        <section className="wi-card" aria-labelledby="ob-title">
          <h2 id="ob-title">Getting consultants signed in</h2>

          {!summary.mailConfigured && (
            <p className="alert error" role="status">
              Email is not set up yet, so login details cannot be sent. IT needs to fill in the
              Microsoft 365 settings (see docs/consultant-onboarding.md, &quot;Mail set-up&quot;).
            </p>
          )}
          {notice && (
            <p
              className={`alert ${notice.kind}`}
              role={notice.kind === 'error' ? 'alert' : 'status'}
            >
              {notice.text}
            </p>
          )}
          {progress && (
            <p className="muted" role="status">
              {progress}
            </p>
          )}

          <ol className="ob-steps">
            <li>
              <strong>Import the weekly register.</strong> <Link to="/import">Import Excel</Link>{' '}
              registers new consultants from their emails and assigns their new clients. The import
              page also keeps the import history.
            </li>
            <li>
              <strong>Generate temporary passwords</strong> for the {summary.eligibleForLogin}{' '}
              consultant{summary.eligibleForLogin === 1 ? '' : 's'} without a login. Consultants who
              already have one are never changed.
              <div className="wi-actions">
                <button
                  type="button"
                  className="btn primary"
                  disabled={busy || summary.eligibleForLogin === 0}
                  onClick={() => void withPassword(generateAll)}
                >
                  Generate temporary passwords
                </button>
              </div>
            </li>
            <li>
              <strong>Send login details.</strong> Each consultant gets one email with the sign-in
              address{summary.loginUrl ? ` (${summary.loginUrl})` : ''}, their email and their own
              temporary password
              {summary.mailSender ? `, from ${summary.mailSender}` : ''}. They must choose a new
              password when they first sign in.
              <div className="wi-actions">
                <button
                  type="button"
                  className="btn primary"
                  disabled={busy || summary.invites.PENDING === 0 || !summary.mailConfigured}
                  onClick={() => void withPassword(sendAll)}
                >
                  Send login details ({summary.invites.PENDING})
                </button>
              </div>
            </li>
          </ol>

          {askPassword && (
            <ConfirmPassword
              onCancel={() => setAskPassword(null)}
              onConfirmed={() => {
                const action = askPassword;
                setAskPassword(null);
                void withPassword(action);
              }}
            />
          )}

          {skipped.length > 0 && (
            <>
              <h3>Not given a login</h3>
              <ul>
                {skipped.map((s) => (
                  <li key={s.consultantId}>
                    {s.fullName ?? `Consultant ${s.consultantId}`}
                    {s.consultantCode ? ` (${s.consultantCode})` : ''}: {s.reason}
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>

        <DeliveryReport
          summary={summary}
          refreshKey={refreshKey}
          busy={busy}
          onRetry={() => void withPassword(retryFailed)}
        />
        <NeedsAttention onChanged={() => void reload()} />
      </div>
    </ListPage>
  );
}
