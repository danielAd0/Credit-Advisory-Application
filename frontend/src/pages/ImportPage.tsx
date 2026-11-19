import { useCallback, useEffect, useState, type ChangeEvent } from 'react';
import {
  detailOf,
  imports,
  isStale,
  type ConfirmResult,
  type ConsultantAttention,
  type ConsultantOutcome,
  type ImportBlocker,
  type ImportHistoryItem,
  type ImportIssue,
  type ImportResult,
  type ImportRow,
  type ImportSummary,
  type PageResponse,
  type RowAction,
  type Severity,
} from '../api';
import { hasAnyRole, useSession } from '../auth/useAuth';
import ListPage from '../components/ListPage';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';
import './imports.css';

const MAX_BYTES = 10 * 1024 * 1024;

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', minimumFractionDigits: 2 });
const day = (iso: string) => new Date(iso).toLocaleDateString('en-ZA');

const BLOCKER_TEXT: Record<ImportBlocker, string> = {
  CONSULTANT_CONFLICTS:
    'Some consultants cannot be linked safely. Fix them in the workbook (see the consultant list) and upload it again.',
  CONSULTANTS_NOT_CONFIRMED:
    'Check the consultants found in the workbook and click Confirm Consultants and Proceed.',
  NAME_DIFFERENCES:
    'Some ID numbers belong to clients stored under another name. Check the issues before confirming.',
  SHEET_ERRORS: 'The workbook has errors that stop the import. Fix it and upload it again.',
  EXPIRED: 'This preview has expired. Upload the file again.',
  NOT_PREVIEWED: 'This import is already finished.',
};

const STATE_LABEL: Partial<Record<string, string>> = {
  PREVIEWED: 'Waiting for confirmation',
  COMMITTED: 'Imported',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

function UploadCard({ onPreview }: { onPreview: (s: ImportSummary) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [week, setWeek] = useState('');
  const [hint, setHint] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function pick(e: ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0] ?? null;
    setError(null);
    setFile(null);
    setWeek('');
    setHint(null);
    if (!f) return;
    if (!f.name.toLowerCase().endsWith('.xlsx'))
      return setError('Choose an Excel workbook that ends in .xlsx.');
    if (f.size === 0) return setError('That file is empty.');
    if (f.size > MAX_BYTES) return setError('That file is larger than 10 MB.');
    setFile(f);
    try {
      const guessed = await imports.weekGuess(f.name);
      if (guessed) {
        setWeek(guessed);
        setHint('Read from the file name. Change it if it is wrong.');
      } else {
        setHint('The file name has no date, so choose the Monday of the week.');
      }
    } catch {
      setHint('Choose the Monday of the week.');
    }
  }

  async function submit() {
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    try {
      onPreview(await imports.preview(file, week || undefined));
    } catch (err) {
      setError(detailOf(err) ?? 'Could not check the file. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="wi-card" aria-label="Upload a weekly register">
      <h2>Upload a weekly register</h2>
      <p className="muted">
        Nothing is saved yet. You will see a summary and can confirm or cancel.
      </p>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      <div className="wi-row">
        <div className="ac-field">
          <label htmlFor="wi-file">Excel workbook (.xlsx)</label>
          <input
            id="wi-file"
            className="wi-file"
            type="file"
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={pick}
          />
        </div>
        <div className="ac-field">
          <label htmlFor="wi-week">Week starting (Monday)</label>
          <input
            id="wi-week"
            type="date"
            value={week}
            onChange={(e) => setWeek(e.target.value)}
            disabled={!file}
          />
        </div>
        <button type="button" className="btn primary" onClick={submit} disabled={!file || busy}>
          {busy ? 'Checking...' : 'Check file'}
        </button>
      </div>
      {hint && <p className="ac-hint">{hint}</p>}
    </section>
  );
}

function History({ onOpen }: { onOpen: (id: number) => void }) {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<ImportHistoryItem> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    imports
      .list(page, 10)
      .then((r) => live && setResult(r))
      .catch(() => live && setError('Could not load the import history.'));
    return () => {
      live = false;
    };
  }, [page]);

  return (
    <section className="wi-card" aria-label="Import history">
      <h2>Previous imports</h2>
      {error && <p className="alert error">{error}</p>}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && (
        <p className="muted">Nothing has been imported yet.</p>
      )}
      {result && result.items.length > 0 && (
        <>
          <div className="lp-table">
            <table>
              <thead>
                <tr>
                  <th>Week</th>
                  <th>File</th>
                  <th>Status</th>
                  <th>Rows</th>
                  <th>Uploaded by</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((h) => (
                  <tr key={h.id}>
                    <td className="lp-strong">{day(h.weekStarting)}</td>
                    <td className="lp-code">
                      <span className="wi-fname">{h.fileName}</span>
                    </td>
                    <td>
                      <span className={`badge wi-state ${h.state.toLowerCase()}`}>
                        {STATE_LABEL[h.state] ?? h.state}
                      </span>
                    </td>
                    <td>
                      {h.state === 'COMMITTED'
                        ? `${h.appliedRows} of ${h.totalRows}`
                        : `${h.totalRows}`}
                      {h.rejectedRows > 0 && ` (${h.rejectedRows} rejected)`}
                    </td>
                    <td>{h.uploadedBy}</td>
                    <td>
                      <button type="button" className="btn lp-row-btn" onClick={() => onOpen(h.id)}>
                        {h.state === 'PREVIEWED' ? 'Review' : 'View'}
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
    </section>
  );
}

function Details({ id }: { id: number }) {
  const [tab, setTab] = useState<'issues' | 'rows'>('issues');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [action, setAction] = useState<RowAction | ''>('');
  const [page, setPage] = useState(0);
  const [issues, setIssues] = useState<PageResponse<ImportIssue> | null>(null);
  const [rows, setRows] = useState<PageResponse<ImportRow> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  async function downloadIssues() {
    setDownloading(true);
    try {
      await imports.exportIssues(id, severity || undefined);
      setError(null);
    } catch (err) {
      setError(detailOf(err) ?? 'Could not download the issues file.');
    } finally {
      setDownloading(false);
    }
  }

  useEffect(() => {
    let live = true;
    const fail = () => live && setError('Could not load the details.');
    if (tab === 'issues') {
      imports
        .issues(id, { severity: severity || undefined, page, size: 25 })
        .then((r) => live && (setIssues(r), setError(null)))
        .catch(fail);
    } else {
      imports
        .rows(id, { action: action || undefined, page, size: 25 })
        .then((r) => live && (setRows(r), setError(null)))
        .catch(fail);
    }
    return () => {
      live = false;
    };
  }, [id, tab, severity, action, page]);

  const current = tab === 'issues' ? issues : rows;

  return (
    <section className="wi-card" aria-label="Details">
      <div className="wi-tabs">
        <button
          type="button"
          className="btn"
          aria-pressed={tab === 'issues'}
          onClick={() => {
            setTab('issues');
            setPage(0);
          }}
        >
          Issues
        </button>
        <button
          type="button"
          className="btn"
          aria-pressed={tab === 'rows'}
          onClick={() => {
            setTab('rows');
            setPage(0);
          }}
        >
          Rows
        </button>
        {tab === 'issues' ? (
          <select
            aria-label="Filter by severity"
            value={severity}
            onChange={(e) => {
              setSeverity(e.target.value as Severity | '');
              setPage(0);
            }}
          >
            <option value="">All issues</option>
            <option value="ERROR">Errors</option>
            <option value="WARNING">Warnings</option>
            <option value="INFO">Notes</option>
          </select>
        ) : (
          <select
            aria-label="Filter by action"
            value={action}
            onChange={(e) => {
              setAction(e.target.value as RowAction | '');
              setPage(0);
            }}
          >
            <option value="">All rows</option>
            <option value="NEW">New clients</option>
            <option value="UNCHANGED">Existing clients</option>
            <option value="DUPLICATE">Repeat clients</option>
            <option value="INVALID">Rejected</option>
          </select>
        )}
        {tab === 'issues' && (
          <button
            type="button"
            className="btn wi-download"
            onClick={() => void downloadIssues()}
            disabled={downloading}
          >
            {downloading ? 'Preparing...' : 'Download issues (CSV)'}
          </button>
        )}
      </div>

      {error && <p className="alert error">{error}</p>}
      {!error && current === null && <p className="muted">Loading...</p>}
      {current && current.items.length === 0 && <p className="muted">Nothing to show.</p>}

      {tab === 'issues' && issues && issues.items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Level</th>
                <th>Where</th>
                <th>Message</th>
              </tr>
            </thead>
            <tbody>
              {issues.items.map((i, n) => (
                <tr key={`${i.code}-${i.sheetName}-${i.rowNumber}-${n}`}>
                  <td className={`wi-sev ${i.severity}`}>{i.severity.toLowerCase()}</td>
                  <td>
                    {i.sheetName ? `${i.sheetName}, row ${i.rowNumber ?? '-'}` : 'Whole file'}
                    {i.field ? ` (${i.field})` : ''}
                  </td>
                  <td>{i.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'rows' && rows && rows.items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Day</th>
                <th>Row</th>
                <th>Action</th>
                <th>ID number</th>
                <th>Client</th>
                <th>Consultant</th>
                <th>Quotation</th>
                <th>Banked</th>
              </tr>
            </thead>
            <tbody>
              {rows.items.map((r) => (
                <tr key={r.id}>
                  <td>{r.sheetName}</td>
                  <td>{r.rowNumber}</td>
                  <td>{r.action.toLowerCase()}</td>
                  <td className="mono">{r.idNumberMasked}</td>
                  <td>
                    {r.firstName || r.lastName ? `${r.firstName ?? ''} ${r.lastName ?? ''}` : '-'}
                  </td>
                  <td>{r.consultantName ?? '-'}</td>
                  <td>{rand(r.quotation)}</td>
                  <td>{rand(r.banked)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {current && (
        <Pager
          page={current.page}
          size={current.size}
          totalItems={current.totalItems}
          totalPages={current.totalPages}
          onPage={setPage}
        />
      )}
    </section>
  );
}

const OUTCOME_LABEL: Record<ConsultantOutcome, string> = {
  EXISTING: 'Existing consultant',
  EMAIL_ADDED: 'Existing consultant, email added',
  NEW: 'New, registered on import',
  CONFLICT: 'Cannot be linked',
};

const ATTENTION_TEXT: Record<Exclude<ConsultantAttention, 'CONFLICT'>, string> = {
  NO_EMAIL: 'No email address. A login can only be created after an email is added.',
  NOT_ACTIVE: 'Not active, so their new clients stay unassigned. Their sales still count for them.',
  SIMILAR_NAME:
    'The name looks like another consultant. If it is the same person, add the CONSULTANT EMAIL column or correct the spelling, then upload again.',
};

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

function ConsultantsCard({
  job,
  onUpdated,
}: {
  job: ImportSummary;
  onUpdated: (s: ImportSummary) => void;
}) {
  const list = job.consultants;
  const asg = job.assignment;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function confirmList() {
    if (busy || !list.fingerprint) return;
    setBusy(true);
    setError(null);
    try {
      onUpdated(await imports.confirmConsultants(job.id, list.fingerprint));
    } catch (err) {
      setError(
        isStale(err)
          ? 'The consultant list changed while you were looking at it. It has been reloaded: check it again.'
          : (detailOf(err) ?? 'Could not confirm the consultants.'),
      );
      try {
        onUpdated(await imports.get(job.id));
      } catch {
        return;
      }
    } finally {
      setBusy(false);
    }
  }

  const table = (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Consultant in the workbook</th>
            <th>Email</th>
            <th>What happens</th>
            <th>Sales rows</th>
            <th>New clients</th>
            <th>Check</th>
          </tr>
        </thead>
        <tbody>
          {list.items.map((c) => (
            <tr key={`${c.outcome}|${c.consultantId ?? ''}|${c.namesInSheet.join('|')}`}>
              <td>
                <strong>{c.namesInSheet[0]}</strong>
                {c.namesInSheet.length > 1 && (
                  <span className="muted">
                    {' '}
                    (also written {c.namesInSheet.slice(1).join(', ')})
                  </span>
                )}
                {c.consultantName && (
                  <div className="muted">
                    {c.consultantName} ({c.consultantCode})
                  </div>
                )}
              </td>
              <td>{c.email ?? <span className="muted">None</span>}</td>
              <td>
                <span className={`wi-outcome ${c.outcome.toLowerCase()}`}>
                  {OUTCOME_LABEL[c.outcome]}
                </span>
                {c.matchedBy && (
                  <div className="muted">
                    Matched by {c.matchedBy === 'EMAIL' ? 'email' : 'name'}
                  </div>
                )}
              </td>
              <td>{c.rows}</td>
              <td>{c.newClients}</td>
              <td>
                {c.reason && <p className="wi-check bad">{c.reason}</p>}
                {c.attention
                  .filter((a): a is Exclude<ConsultantAttention, 'CONFLICT'> => a !== 'CONFLICT')
                  .map((a) => (
                    <p className="wi-check" key={a}>
                      {ATTENTION_TEXT[a]}
                      {a === 'SIMILAR_NAME' && c.similarConsultants.length > 0 && (
                        <>
                          {' '}
                          Similar:{' '}
                          {c.similarConsultants
                            .map((s) => `${s.fullName} (${s.consultantCode})`)
                            .join(', ')}
                          .
                        </>
                      )}
                    </p>
                  ))}
                {!c.reason && c.attention.length === 0 && <span className="muted">-</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  if (list.confirmed) {
    return (
      <section className="wi-card" aria-label="Consultants">
        <h2>Consultants confirmed</h2>
        <p className="alert ok" role="status">
          {plural(list.identified, 'consultant was', 'consultants were')} confirmed
          {list.confirmedBy && ` by ${list.confirmedBy}`}
          {list.confirmedAt && ` on ${day(list.confirmedAt)}`}. {list.toRegister} will be registered
          when you confirm the import.
        </p>
        <details>
          <summary>Show the consultants</summary>
          {table}
        </details>
      </section>
    );
  }

  return (
    <section className="wi-card" aria-label="Check the consultants">
      <h2>Step 1 of 2: check the consultants</h2>
      <p className="muted">
        These are the consultants found in the workbook. Check that they are the right people.
        Nothing is saved yet: new consultants are registered, and every client and sale is linked to
        its consultant, only when you confirm the import.
      </p>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}

      <div className="wi-stats">
        <div className="wi-stat">
          <strong>{list.identified}</strong>
          <span>Consultants found</span>
        </div>
        <div className="wi-stat">
          <strong>{list.existing}</strong>
          <span>Already registered</span>
        </div>
        <div className="wi-stat">
          <strong>{list.toRegister}</strong>
          <span>New, to register</span>
        </div>
        <div className={`wi-stat ${list.conflicts > 0 ? 'bad' : ''}`}>
          <strong>{list.conflicts}</strong>
          <span>Cannot be linked</span>
        </div>
        <div className="wi-stat">
          <strong>{list.withoutEmail}</strong>
          <span>Without an email</span>
        </div>
      </div>

      {list.conflicts > 0 && (
        <p className="alert error" role="status">
          {plural(list.conflicts, 'consultant', 'consultants')} cannot be linked safely, so their
          clients could go to the wrong person. Correct the name or email in the workbook and upload
          it again.
        </p>
      )}

      {list.items.length === 0 ? (
        <p className="muted">
          No consultant names were found. Every sale will be imported without a consultant.
        </p>
      ) : (
        table
      )}

      {asg && asg.newClientRows > 0 && (
        <p className="muted">
          New clients: {asg.willBeAssigned} will be assigned to their consultant,{' '}
          {asg.noConsultant + asg.consultantNotActive} left unassigned ({asg.noConsultant} without a
          consultant in the workbook, {asg.consultantNotActive} whose consultant is not active).
          {asg.existingClientsWithOtherConsultant > 0 &&
            ` ${plural(
              asg.existingClientsWithOtherConsultant,
              'existing client stays',
              'existing clients stay',
            )} with their current consultant; the sale still counts for the consultant in the workbook.`}
        </p>
      )}

      <div className="wi-actions">
        <button
          type="button"
          className="btn primary"
          onClick={confirmList}
          disabled={busy || list.conflicts > 0 || !list.fingerprint}
        >
          {busy ? 'Working...' : 'Confirm Consultants and Proceed'}
        </button>
      </div>
    </section>
  );
}

function ResultCard({ job, result }: { job: ImportSummary; result: ImportResult }) {
  const records = result.transactionsLinked + result.transactionsUnattributed;
  return (
    <section className="wi-card" aria-label="Import summary">
      <h2>Import summary</h2>
      <div className="wi-stats">
        <div className="wi-stat">
          <strong>{result.consultantsIdentified}</strong>
          <span>Consultants found</span>
        </div>
        <div className="wi-stat">
          <strong>{result.consultantsRegistered}</strong>
          <span>New consultants registered</span>
        </div>
        <div className="wi-stat">
          <strong>{result.consultantsMatched}</strong>
          <span>Existing consultants matched</span>
        </div>
        <div className="wi-stat">
          <strong>{result.clientsLinked}</strong>
          <span>Clients imported</span>
        </div>
      </div>
      <div className="wi-stats">
        <div className="wi-stat">
          <strong>{records}</strong>
          <span>Sales records imported</span>
        </div>
        <div className="wi-stat">
          <strong>{result.transactionsLinked}</strong>
          <span>Linked to client and consultant</span>
        </div>
        <div
          className={`wi-stat ${job.rejectedRows + result.transactionsUnattributed > 0 ? 'bad' : ''}`}
        >
          <strong>{job.rejectedRows + result.transactionsUnattributed}</strong>
          <span>Rejected or without a consultant</span>
        </div>
        <div className={`wi-stat ${result.rowsNeedingAttention > 0 ? 'bad' : ''}`}>
          <strong>{result.rowsNeedingAttention}</strong>
          <span>Need attention</span>
        </div>
      </div>
      <p className="muted">
        Every sale keeps its date from the workbook and the consultant who made it. Reports for any
        period use those, also after a client moves to another consultant.
        {result.rowsNeedingAttention > 0 &&
          ' Rows that need attention are in the issues below (rejected rows, sales without a consultant, possible duplicates).'}
        {result.consultantsRegistered > 0 &&
          ' Give the new consultants their logins on Consultant logins.'}
      </p>
    </section>
  );
}

function ImportView({ initial, onBack }: { initial: ImportSummary; onBack: () => void }) {
  const session = useSession();
  const isAdmin = hasAnyRole(session, ['ADMIN']);
  const [job, setJob] = useState(initial);
  const [reason, setReason] = useState('');
  const [acceptNames, setAcceptNames] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ConfirmResult | null>(null);

  const reload = useCallback(async () => setJob(await imports.get(initial.id)), [initial.id]);

  async function confirm() {
    if (busy) return;
    if (job.replaceRequired && !reason.trim())
      return setError('Give a reason for replacing this week.');
    setBusy(true);
    setError(null);
    try {
      setResult(
        await imports.confirm(
          job.id,
          job.fileDigest,
          job.replaceRequired ? reason.trim() : null,
          acceptNames,
        ),
      );
      await reload();
    } catch (err) {
      setError(
        isStale(err)
          ? 'Something changed since the preview. Upload the file again, or check the consultants again.'
          : (detailOf(err) ?? 'Could not confirm the import.'),
      );
      await reload().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      setJob(await imports.cancel(job.id));
    } catch (err) {
      setError(detailOf(err) ?? 'Could not cancel the import.');
    } finally {
      setBusy(false);
    }
  }

  const previewed = job.state === 'PREVIEWED';
  const blocked = previewed && job.replaceRequired && !isAdmin;
  const nameCheckNeeded = previewed && job.blockers.includes('NAME_DIFFERENCES');
  const remaining = job.blockers.filter((b) => !(b === 'NAME_DIFFERENCES' && acceptNames));
  const consultantsPending = previewed && !job.consultants.confirmed;
  const outcome = job.result;

  return (
    <>
      <p>
        <button type="button" className="btn" onClick={onBack}>
          Back to imports
        </button>
      </p>

      {consultantsPending && <ConsultantsCard job={job} onUpdated={setJob} />}

      <section className="wi-card" aria-label="Import details">
        <h2>
          {previewed && !consultantsPending ? 'Step 2 of 2: ' : ''}Week of {day(job.weekStarting)}{' '}
          to {day(job.weekEnding)}
        </h2>
        <p className="muted">
          {job.fileName} · {STATE_LABEL[job.state] ?? job.state} · uploaded by {job.uploadedBy}
        </p>

        {result && (
          <p className="alert ok" role="status">
            Imported. {result.transactions} sales were saved, {result.newClients} new clients were
            created and {result.clientsAssigned} were assigned to a consultant
            {result.employersCreated > 0 &&
              `, and ${result.employersCreated} new employers were added`}
            .
            {result.replacedTypedInSales > 0 &&
              ` It replaced ${result.replacedTypedInSales} sale${
                result.replacedTypedInSales === 1 ? '' : 's'
              } typed into the app for this week.`}
          </p>
        )}
        {error && (
          <p className="alert error" role="alert">
            {error}
          </p>
        )}

        <div className="wi-stats">
          <div className="wi-stat">
            <strong>{job.totalRows}</strong>
            <span>Rows</span>
          </div>
          <div className="wi-stat">
            <strong>{job.newClients}</strong>
            <span>New clients</span>
          </div>
          <div className="wi-stat">
            <strong>{job.existingClients}</strong>
            <span>Existing clients</span>
          </div>
          <div className="wi-stat">
            <strong>{job.repeatRows}</strong>
            <span>Repeat rows</span>
          </div>
          <div className={`wi-stat ${job.rejectedRows > 0 ? 'bad' : ''}`}>
            <strong>{job.rejectedRows}</strong>
            <span>Rejected</span>
          </div>
          {job.possibleDuplicateRows > 0 && (
            <div className="wi-stat bad">
              <strong>{job.possibleDuplicateRows}</strong>
              <span>Possible duplicates</span>
            </div>
          )}
        </div>
        <p>
          Quotation <strong>{rand(job.quotationTotal)}</strong> · Banked{' '}
          <strong>{rand(job.bankedTotal)}</strong>
          <span className="muted">
            {' '}
            · {job.errors} errors, {job.warnings} warnings, {job.infos} notes
          </span>
        </p>

        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Day</th>
                <th>Date</th>
                <th>Rows</th>
                <th>Rejected</th>
                <th>Quotation</th>
                <th>Banked</th>
              </tr>
            </thead>
            <tbody>
              {job.days.map((d) => (
                <tr key={d.dayOfWeek}>
                  <td>{d.sheetName ?? <span className="muted">No sheet</span>}</td>
                  <td>{d.date ? day(d.date) : '-'}</td>
                  <td>{d.rows}</td>
                  <td>{d.rejected}</td>
                  <td>{rand(d.quotation)}</td>
                  <td>{rand(d.banked)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {previewed && remaining.length > 0 && (
          <ul className="alert error">
            {remaining.map((b) => (
              <li key={b}>{BLOCKER_TEXT[b]}</li>
            ))}
          </ul>
        )}

        {nameCheckNeeded && (
          <label className="wi-accept">
            <input
              type="checkbox"
              checked={acceptNames}
              onChange={(e) => setAcceptNames(e.target.checked)}
            />{' '}
            I checked the {plural(job.nameDifferences, 'client', 'clients')} whose ID number is
            stored under another name (issues with code client:name-differs). The stored names are
            kept.
          </label>
        )}

        {previewed && job.typedInSales > 0 && (
          <p className="alert error" role="status">
            {job.typedInSales} sale{job.typedInSales === 1 ? ' was' : 's were'} typed into the app
            for this week. Confirming replaces {job.typedInSales === 1 ? 'it' : 'them'} with this
            spreadsheet, so check that the spreadsheet has every sale of the week first.
          </p>
        )}

        {previewed && job.replaceRequired && (
          <div style={{ marginTop: '1rem' }}>
            <p className="alert error" role="status">
              This week was already imported. Confirming replaces it.
              {!isAdmin && ' Only an administrator can do that.'}
            </p>
            {isAdmin && (
              <div className="ac-field">
                <label htmlFor="wi-reason">Reason for replacing</label>
                <textarea
                  id="wi-reason"
                  maxLength={500}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </div>
            )}
          </div>
        )}

        {previewed && (
          <div className="wi-actions">
            <button
              type="button"
              className="btn primary"
              onClick={confirm}
              disabled={busy || remaining.length > 0 || blocked}
            >
              {busy ? 'Working...' : 'Confirm import'}
            </button>
            <button type="button" className="btn" onClick={cancel} disabled={busy}>
              Cancel import
            </button>
          </div>
        )}
      </section>

      {outcome && <ResultCard job={job} result={outcome} />}
      {previewed && !consultantsPending && <ConsultantsCard job={job} onUpdated={setJob} />}
      <Details id={job.id} />
    </>
  );
}

export default function ImportPage() {
  const [job, setJob] = useState<ImportSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [historyKey, setHistoryKey] = useState(0);

  async function open(id: number) {
    setError(null);
    try {
      setJob(await imports.get(id));
    } catch (err) {
      setError(detailOf(err) ?? 'Could not open that import.');
    }
  }

  return (
    <ListPage
      title="Import weekly register"
      description={
        <>
          Upload the weekly Credit Advisory workbook. You check the consultants found in it, then
          confirm: new consultants are registered and every client and sale is linked to its
          consultant, keeping the dates in the workbook. ID numbers are always shown masked.
        </>
      }
      footer="Import history is kept"
    >
      <div className="wi">
        {error && (
          <p className="alert error" role="alert">
            {error}
          </p>
        )}
        {job ? (
          <ImportView
            key={job.id}
            initial={job}
            onBack={() => {
              setJob(null);
              setHistoryKey((n) => n + 1);
            }}
          />
        ) : (
          <>
            <UploadCard onPreview={setJob} />
            <History key={historyKey} onOpen={open} />
          </>
        )}
      </div>
    </ListPage>
  );
}
