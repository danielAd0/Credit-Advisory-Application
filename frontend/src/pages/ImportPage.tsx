import { useCallback, useEffect, useState, type ChangeEvent } from 'react';
import {
  data,
  detailOf,
  imports,
  isStale,
  type ConfirmResult,
  type ConsultantSummary,
  type ImportBlocker,
  type ImportHistoryItem,
  type ImportIssue,
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
  UNMAPPED_CONSULTANTS: 'Match each consultant name below to a consultant.',
  CONSULTANT_CONFLICTS:
    'Some consultant names do not match their email. Fix them in the workbook (see below) and upload it again.',
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
                    <td className="lp-code">{h.fileName}</td>
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

function ConsultantMapping({
  job,
  onUpdated,
}: {
  job: ImportSummary;
  onUpdated: (s: ImportSummary) => void;
}) {
  const [options, setOptions] = useState<ConsultantSummary[]>([]);
  const [choice, setChoice] = useState<Partial<Record<string, string>>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    data
      .listConsultants()
      .then((r) => live && setOptions(r.filter((c) => c.status === 'ACTIVE')))
      .catch(() => live && setError('Could not load consultants.'));
    return () => {
      live = false;
    };
  }, []);

  async function map(name: string) {
    const id = Number(choice[name]);
    if (!id || busy) return;
    setBusy(name);
    setError(null);
    try {
      onUpdated(await imports.mapConsultant(job.id, name, id));
    } catch (err) {
      setError(detailOf(err) ?? 'Could not match that name.');
    } finally {
      setBusy(null);
    }
  }

  if (job.unmappedConsultants.length === 0) return null;
  return (
    <section className="wi-card" aria-label="Match consultants">
      <h2>Match consultant names</h2>
      <p className="muted">
        These names in the workbook are not recognised yet. The match is remembered for later
        imports.
      </p>
      {error && <p className="alert error">{error}</p>}
      {job.unmappedConsultants.map((u) => (
        <div className="wi-map" key={u.nameInSheet}>
          <strong>{u.nameInSheet}</strong>
          <span className="muted">
            {u.rows} row{u.rows === 1 ? '' : 's'}
          </span>
          <select
            aria-label={`Consultant for ${u.nameInSheet}`}
            value={choice[u.nameInSheet] ?? ''}
            onChange={(e) => setChoice({ ...choice, [u.nameInSheet]: e.target.value })}
          >
            <option value="">Choose a consultant</option>
            {options.map((c) => (
              <option key={c.id} value={c.id}>
                {c.fullName} ({c.consultantCode})
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn"
            disabled={!choice[u.nameInSheet] || busy !== null}
            onClick={() => map(u.nameInSheet)}
          >
            Match
          </button>
        </div>
      ))}
    </section>
  );
}

function ConsultantRegistrationCard({ job }: { job: ImportSummary }) {
  const reg = job.consultantRegistration;
  const asg = job.assignment;
  if (!reg || !asg) return null;
  const hasEmailWork =
    reg.toRegister.length > 0 || reg.emailsToAdd.length > 0 || reg.conflicts.length > 0;
  if (!hasEmailWork && reg.matchedByEmail === 0 && asg.newClientRows === 0) return null;
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

  return (
    <section className="wi-card" aria-label="Consultants and assignment">
      <h2>Consultants and client assignment</h2>
      <p className="muted">
        {plural(reg.namesInSheet, 'consultant name', 'consultant names')} in the workbook:{' '}
        {reg.matchedByName} matched by name, {reg.matchedByEmail} by email
        {reg.toRegister.length > 0 && `, ${reg.toRegister.length} new`}.
      </p>

      <div className="wi-stats">
        <div className="wi-stat">
          <strong>{asg.willBeAssigned}</strong>
          <span>New clients assigned</span>
        </div>
        <div className={`wi-stat ${asg.waitingForConsultant > 0 ? 'bad' : ''}`}>
          <strong>{asg.waitingForConsultant}</strong>
          <span>Waiting for a consultant match</span>
        </div>
        <div className="wi-stat">
          <strong>{asg.noConsultant + asg.consultantNotActive}</strong>
          <span>Left unassigned</span>
        </div>
        <div className="wi-stat">
          <strong>{asg.existingClientsWithOtherConsultant}</strong>
          <span>Existing clients kept with their consultant</span>
        </div>
      </div>
      {(asg.noConsultant > 0 || asg.consultantNotActive > 0) && (
        <p className="muted">
          Unassigned: {asg.noConsultant} without a consultant in the sheet,{' '}
          {asg.consultantNotActive} whose consultant is inactive or suspended. Assign them later on
          Reassign clients.
        </p>
      )}

      {reg.conflicts.length > 0 && (
        <>
          <h3>Names that do not match their email</h3>
          <p className="alert error" role="status">
            These rows could give clients to the wrong consultant, so the import cannot be
            confirmed. Correct the name or email in the workbook and upload it again. To fill in a
            consultant&apos;s missing email, use Consultant logins.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name in sheet</th>
                  <th>Email</th>
                  <th>Rows</th>
                  <th>Problem</th>
                </tr>
              </thead>
              <tbody>
                {reg.conflicts.map((c) => (
                  <tr key={`${c.nameInSheet}|${c.email}`}>
                    <td>{c.nameInSheet}</td>
                    <td>{c.email}</td>
                    <td>{c.rows}</td>
                    <td>{c.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {reg.toRegister.length > 0 && (
        <>
          <h3>New consultants (registered when you confirm)</h3>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Email</th>
                  <th>Rows</th>
                  <th>Check</th>
                </tr>
              </thead>
              <tbody>
                {reg.toRegister.map((r) => (
                  <tr key={r.email}>
                    <td>
                      {r.fullName}
                      {r.otherSpellings.length > 0 && (
                        <span className="muted"> (also written {r.otherSpellings.join(', ')})</span>
                      )}
                    </td>
                    <td>{r.email}</td>
                    <td>{r.rows}</td>
                    <td>
                      {r.similarConsultants.length > 0 ? (
                        <span>
                          Similar to{' '}
                          {r.similarConsultants
                            .map((c) => `${c.fullName} (${c.consultantCode})`)
                            .join(', ')}
                          . If it is the same person, give that consultant this email first.
                        </span>
                      ) : (
                        <span className="muted">-</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {reg.emailsToAdd.length > 0 && (
        <>
          <h3>Emails added to existing consultants</h3>
          <ul>
            {reg.emailsToAdd.map((u) => (
              <li key={u.consultantId}>
                {u.fullName} ({u.consultantCode}): {u.email}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

function ImportView({ initial, onBack }: { initial: ImportSummary; onBack: () => void }) {
  const session = useSession();
  const isAdmin = hasAnyRole(session, ['ADMIN']);
  const [job, setJob] = useState(initial);
  const [reason, setReason] = useState('');
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
        await imports.confirm(job.id, job.fileDigest, job.replaceRequired ? reason.trim() : null),
      );
      await reload();
    } catch (err) {
      setError(
        isStale(err)
          ? 'Something changed since the preview. Upload the file again.'
          : (detailOf(err) ?? 'Could not confirm the import.'),
      );
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

  return (
    <>
      <p>
        <button type="button" className="btn" onClick={onBack}>
          Back to imports
        </button>
      </p>

      <section className="wi-card" aria-label="Import summary">
        <h2>
          Week of {day(job.weekStarting)} to {day(job.weekEnding)}
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
            {(result.consultantsRegistered ?? 0) > 0 &&
              ` ${result.consultantsRegistered} new consultant${
                result.consultantsRegistered === 1 ? ' was' : 's were'
              } registered: give them their logins on Consultant logins.`}
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

        {previewed && job.blockers.length > 0 && (
          <ul className="alert error">
            {job.blockers.map((b) => (
              <li key={b}>{BLOCKER_TEXT[b]}</li>
            ))}
          </ul>
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
              disabled={busy || !job.canConfirm || blocked}
            >
              {busy ? 'Working...' : 'Confirm import'}
            </button>
            <button type="button" className="btn" onClick={cancel} disabled={busy}>
              Cancel import
            </button>
          </div>
        )}
      </section>

      {previewed && <ConsultantRegistrationCard job={job} />}
      {previewed && <ConsultantMapping job={job} onUpdated={setJob} />}
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
          Upload the weekly Credit Advisory workbook. New clients and the week&rsquo;s sales are
          added after you confirm. ID numbers are always shown masked.
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
