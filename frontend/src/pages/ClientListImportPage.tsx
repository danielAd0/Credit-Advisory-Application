import { useEffect, useState, type ChangeEvent } from 'react';
import {
  clientListImports,
  detailOf,
  isStale,
  type ClientListAction,
  type ClientListBlocker,
  type ClientListConfirmResult,
  type ClientListHistoryItem,
  type ClientListIssue,
  type ClientListRow,
  type ClientListSeverity,
  type ClientListSummary,
  type PageResponse,
} from '../api';
import ListPage from '../components/ListPage';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';
import './imports.css';

const MAX_BYTES = 10 * 1024 * 1024;

const stamp = (iso: string | null) => (iso ? new Date(iso).toLocaleString('en-ZA') : '-');
const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

const STATE_LABEL: Record<string, string> = {
  PREVIEWED: 'Waiting for confirmation',
  COMMITTED: 'Imported',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

const BLOCKER_TEXT: Record<ClientListBlocker, string> = {
  FILE_ERRORS: 'The file has errors. Fix them in the workbook and upload it again.',
  CONSULTANT_CONFLICTS:
    'Some consultants in the file cannot be linked. Fix the names or emails in the workbook and upload it again.',
  NOTHING_TO_IMPORT: 'There is nothing new in this file to import.',
  EXPIRED: 'This check has expired. Upload the file again.',
  FINISHED: 'This import is finished.',
};

const ACTION_LABEL: Record<ClientListAction, string> = {
  NEW: 'New client',
  UPDATE: 'Fills in details',
  UNCHANGED: 'No change',
  CONFLICT: 'Conflict',
  DUPLICATE: 'Repeat in file',
  INVALID: 'Rejected',
};

function UploadCard({ onPreview }: { onPreview: (s: ClientListSummary) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function pick(e: ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0] ?? null;
    setError(null);
    setFile(null);
    if (!f) return;
    if (!f.name.toLowerCase().endsWith('.xlsx')) {
      setError('Choose an Excel workbook that ends in .xlsx.');
      return;
    }
    if (f.size === 0) {
      setError('That file is empty.');
      return;
    }
    if (f.size > MAX_BYTES) {
      setError('That file is larger than 10 MB.');
      return;
    }
    setFile(f);
  }

  async function submit() {
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    try {
      onPreview(await clientListImports.preview(file));
    } catch (err) {
      setError(detailOf(err) ?? 'Could not check the file. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="wi-card" aria-label="Upload a client list">
      <h2>Upload a client list</h2>
      <p className="muted">
        Nothing is saved yet. You will see what the file would add and can confirm or cancel.
      </p>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      <div className="wi-row">
        <div className="ac-field">
          <label htmlFor="cl-file">Excel workbook (.xlsx)</label>
          <input
            id="cl-file"
            className="wi-file"
            type="file"
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={pick}
          />
        </div>
        <button type="button" className="btn primary" onClick={submit} disabled={!file || busy}>
          {busy ? 'Checking...' : 'Check file'}
        </button>
      </div>
    </section>
  );
}

function History({ onOpen }: { onOpen: (id: number) => void }) {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<ClientListHistoryItem> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    clientListImports
      .list(page, 10)
      .then((r) => live && setResult(r))
      .catch(() => live && setError('Could not load the import history.'));
    return () => {
      live = false;
    };
  }, [page]);

  return (
    <section className="wi-card" aria-label="Client list history">
      <h2>Previous client list imports</h2>
      {error && <p className="alert error">{error}</p>}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && (
        <p className="muted">No client list has been uploaded yet.</p>
      )}
      {result && result.items.length > 0 && (
        <>
          <div className="lp-table">
            <table>
              <thead>
                <tr>
                  <th>File</th>
                  <th>Status</th>
                  <th>Rows</th>
                  <th>New clients</th>
                  <th>Uploaded by</th>
                  <th>Uploaded</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((h) => (
                  <tr key={h.id}>
                    <td className="lp-code">
                      <span className="wi-fname">{h.fileName}</span>
                    </td>
                    <td>
                      <span className={`badge wi-state ${h.state.toLowerCase()}`}>
                        {STATE_LABEL[h.state] ?? h.state}
                      </span>
                    </td>
                    <td>{h.totalRows}</td>
                    <td>{h.newClients}</td>
                    <td>{h.uploadedBy}</td>
                    <td>{stamp(h.createdAt)}</td>
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

function Stat({ value, label, bad }: { value: number; label: string; bad?: boolean }) {
  return (
    <div className={`wi-stat${bad && value > 0 ? ' bad' : ''}`}>
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

function Details({ id }: { id: number }) {
  const [tab, setTab] = useState<'issues' | 'rows'>('issues');
  const [severity, setSeverity] = useState<ClientListSeverity | ''>('');
  const [action, setAction] = useState<ClientListAction | ''>('');
  const [page, setPage] = useState(0);
  const [issues, setIssues] = useState<PageResponse<ClientListIssue> | null>(null);
  const [rows, setRows] = useState<PageResponse<ClientListRow> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  async function downloadIssues() {
    setDownloading(true);
    try {
      await clientListImports.exportIssues(id, severity || undefined);
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
      clientListImports
        .issues(id, { severity: severity || undefined, page, size: 25 })
        .then((r) => live && (setIssues(r), setError(null)))
        .catch(fail);
    } else {
      clientListImports
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
              setSeverity(e.target.value as ClientListSeverity | '');
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
              setAction(e.target.value as ClientListAction | '');
              setPage(0);
            }}
          >
            <option value="">All rows</option>
            {(Object.keys(ACTION_LABEL) as ClientListAction[]).map((a) => (
              <option key={a} value={a}>
                {ACTION_LABEL[a]}
              </option>
            ))}
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
                <tr key={`${i.code}-${i.rowNumber}-${n}`}>
                  <td className={`wi-sev ${i.severity}`}>{i.severity.toLowerCase()}</td>
                  <td>
                    {i.rowNumber === null ? 'Whole file' : `Row ${i.rowNumber}`}
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
                <th>Row</th>
                <th>Action</th>
                <th>ID number</th>
                <th>Client</th>
                <th>Consultant</th>
                <th>Warnings</th>
              </tr>
            </thead>
            <tbody>
              {rows.items.map((r) => (
                <tr key={r.rowNumber}>
                  <td>{r.rowNumber}</td>
                  <td>{ACTION_LABEL[r.action]}</td>
                  <td className="mono">{r.idNumber ?? '-'}</td>
                  <td>
                    {r.firstName || r.lastName ? `${r.firstName ?? ''} ${r.lastName ?? ''}` : '-'}
                  </td>
                  <td>{r.consultantName ?? '-'}</td>
                  <td>{r.warningCount > 0 ? r.warningCount : '-'}</td>
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

function SummaryCards({ job }: { job: ClientListSummary }) {
  const { rows, additions: a, consultants: c } = job;
  return (
    <>
      <section className="wi-card" aria-label="What the file contains">
        <h2>{job.fileName}</h2>
        <p className="muted">
          {job.sheetName ? `Sheet "${job.sheetName}". ` : ''}Uploaded by {job.uploadedBy} on{' '}
          {stamp(job.createdAt)}.{' '}
          {job.state === 'PREVIEWED' && `Valid until ${stamp(job.expiresAt)}.`}
        </p>
        <div className="wi-stats">
          <Stat value={rows.total} label="Rows in the file" />
          <Stat value={rows.newClients} label="New clients" />
          <Stat value={rows.update} label="Existing clients to fill in" />
          <Stat value={rows.unchanged} label="No change" />
          <Stat value={rows.duplicate} label="Repeated in the file" />
          <Stat value={rows.conflict} label="Conflicts" bad />
          <Stat value={rows.invalid} label="Rejected rows" bad />
          <Stat value={job.issues.errors} label="Errors" bad />
          <Stat value={job.issues.warnings} label="Warnings" />
        </div>
      </section>

      <section className="wi-card" aria-label="What will be added">
        <h2>What will be added</h2>
        <div className="wi-stats">
          <Stat value={a.withCellphone} label="With a cellphone" />
          <Stat value={a.withWorkPhone} label="With a work phone" />
          <Stat value={a.withHomePhone} label="With a home phone" />
          <Stat value={a.withEmail} label="With an email" />
          <Stat value={a.withFileNumber} label="With a file number" />
          <Stat value={a.withFileId} label="With a file ID" />
          <Stat value={a.withEmployer} label="With an employer" />
          <Stat value={a.withProductQuotation} label="With a product quotation" />
          <Stat value={a.debitOrders} label="Debit orders" />
          <Stat value={a.withoutConsultant} label="New clients with no consultant" />
        </div>
        {(job.newEmployers.length > 0 || job.newProductQuotations.length > 0) && (
          <>
            {job.newEmployers.length > 0 && (
              <p>
                <strong>{plural(job.newEmployers.length, 'new employer', 'new employers')}:</strong>{' '}
                {job.newEmployers.join(', ')}
              </p>
            )}
            {job.newProductQuotations.length > 0 && (
              <p>
                <strong>
                  {plural(
                    job.newProductQuotations.length,
                    'new product quotation',
                    'new product quotations',
                  )}
                  :
                </strong>{' '}
                {job.newProductQuotations.join(', ')}
              </p>
            )}
          </>
        )}
        <details>
          <summary>How the columns were read ({job.columns.length})</summary>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Column in the file</th>
                  <th>Used as</th>
                </tr>
              </thead>
              <tbody>
                {job.columns.map((col) => (
                  <tr key={col.header}>
                    <td>{col.header}</td>
                    <td>{col.field ?? <span className="muted">Ignored</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      </section>

      <section className="wi-card" aria-label="Consultants">
        <h2>Consultants in the file</h2>
        {c.matched.length + c.toRegister.length + c.emailsToAdd.length + c.conflicts.length ===
          0 && <p className="muted">The file names no consultants.</p>}
        {c.conflicts.length > 0 && (
          <>
            <h3>Cannot be linked ({c.conflicts.length})</h3>
            <p className="muted">Fix these in the workbook and upload it again.</p>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Name in the file</th>
                    <th>Email</th>
                    <th>Problem</th>
                    <th>Rows</th>
                  </tr>
                </thead>
                <tbody>
                  {c.conflicts.map((x) => (
                    <tr key={`${x.nameInSheet}|${x.email ?? ''}`}>
                      <td>{x.nameInSheet}</td>
                      <td>{x.email ?? '-'}</td>
                      <td className="wi-check bad">{x.reason}</td>
                      <td>{x.rows}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        {c.toRegister.length > 0 && (
          <>
            <h3>Will be registered ({c.toRegister.length})</h3>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Email</th>
                    <th>Also written</th>
                    <th>Rows</th>
                  </tr>
                </thead>
                <tbody>
                  {c.toRegister.map((x) => (
                    <tr key={`${x.fullName}|${x.email ?? ''}`}>
                      <td>{x.fullName}</td>
                      <td>{x.email ?? <span className="muted">None</span>}</td>
                      <td>{x.otherSpellings.length > 0 ? x.otherSpellings.join(', ') : '-'}</td>
                      <td>{x.rows}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        {c.emailsToAdd.length > 0 && (
          <>
            <h3>Email will be added ({c.emailsToAdd.length})</h3>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Consultant</th>
                    <th>Email to add</th>
                  </tr>
                </thead>
                <tbody>
                  {c.emailsToAdd.map((x) => (
                    <tr key={x.consultantId}>
                      <td>
                        {x.fullName} ({x.consultantCode})
                      </td>
                      <td>{x.email}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        {c.matched.length > 0 && (
          <>
            <h3>Already registered ({c.matched.length})</h3>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Consultant</th>
                    <th>Status</th>
                    <th>Written in the file as</th>
                    <th>Rows</th>
                  </tr>
                </thead>
                <tbody>
                  {c.matched.map((x) => (
                    <tr key={x.consultantId}>
                      <td>
                        {x.fullName} ({x.consultantCode})
                      </td>
                      <td>{x.status.toLowerCase()}</td>
                      <td>{x.namesInSheet.join(', ')}</td>
                      <td>{x.rows}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>
    </>
  );
}

function ResultCard({ r }: { r: ClientListConfirmResult }) {
  return (
    <section className="wi-card" aria-label="Import result">
      <h2>Client list imported</h2>
      <div className="wi-stats">
        <Stat value={r.newClients} label="New clients added" />
        <Stat value={r.updatedClients} label="Existing clients filled in" />
        <Stat value={r.unchangedClients} label="No change" />
        <Stat value={r.assigned} label="Linked to a consultant" />
        <Stat value={r.newClientsWithoutConsultant} label="New clients with no consultant" />
        <Stat value={r.consultantsRegistered} label="Consultants registered" />
        <Stat value={r.consultantEmailsAdded} label="Consultant emails added" />
        <Stat value={r.employersCreated} label="Employers created" />
        <Stat value={r.productQuotationsCreated} label="Product quotations created" />
        <Stat value={r.skippedRows} label="Rows skipped" />
        <Stat value={r.staleRows} label="Rows changed since the check" bad />
      </div>
    </section>
  );
}

function ImportView({ initial, onBack }: { initial: ClientListSummary; onBack: () => void }) {
  const [job, setJob] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ClientListConfirmResult | null>(null);

  async function reload() {
    try {
      setJob(await clientListImports.get(job.id));
    } catch {
      return;
    }
  }

  async function confirm() {
    if (busy || !job.canConfirm) return;
    setBusy(true);
    setError(null);
    try {
      setResult(
        await clientListImports.confirm(job.id, job.fileDigest, job.consultants.fingerprint),
      );
      await reload();
    } catch (err) {
      if (isStale(err) || (err as { type?: string }).type?.endsWith('import:consultants-changed')) {
        setError(
          'The consultant list changed. It has been reloaded: check it, then confirm again.',
        );
        await reload();
      } else {
        setError(detailOf(err) ?? 'Could not import the client list.');
        await reload();
      }
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      setJob(await clientListImports.cancel(job.id));
    } catch (err) {
      setError(detailOf(err) ?? 'Could not cancel the import.');
    } finally {
      setBusy(false);
    }
  }

  const open = job.state === 'PREVIEWED';

  return (
    <>
      <p>
        <button type="button" className="btn" onClick={onBack}>
          Back to client list imports
        </button>
      </p>
      {error && (
        <p className="alert error" role="alert">
          {error}
        </p>
      )}
      {result && <ResultCard r={result} />}
      {!result && job.state !== 'PREVIEWED' && (
        <p className="alert">
          {STATE_LABEL[job.state] ?? job.state}
          {job.committedAt ? ` on ${stamp(job.committedAt)}` : ''}.
        </p>
      )}
      {!result && job.state === 'COMMITTED' && job.result && (
        <p className="muted">
          {plural(job.result.newClients, 'new client', 'new clients')} added,{' '}
          {plural(job.result.clientsLinked, 'client', 'clients')} linked to a consultant.
        </p>
      )}
      {open && job.blockers.length > 0 && (
        <div className="alert error" role="alert">
          {job.blockers.map((b) => (
            <p key={b}>{BLOCKER_TEXT[b]}</p>
          ))}
        </div>
      )}
      <SummaryCards job={job} />
      <Details id={job.id} />
      {open && (
        <div className="wi-actions">
          <button
            type="button"
            className="btn primary"
            onClick={() => void confirm()}
            disabled={busy || !job.canConfirm}
          >
            {busy ? 'Working...' : 'Confirm import'}
          </button>
          <button type="button" className="btn" onClick={() => void cancel()} disabled={busy}>
            Cancel import
          </button>
        </div>
      )}
    </>
  );
}

export default function ClientListImportPage() {
  const [job, setJob] = useState<ClientListSummary | null>(null);
  const [historyKey, setHistoryKey] = useState(0);
  const [error, setError] = useState<string | null>(null);

  async function open(id: number) {
    setError(null);
    try {
      setJob(await clientListImports.get(id));
    } catch (err) {
      setError(detailOf(err) ?? 'Could not open that import.');
    }
  }

  return (
    <ListPage
      title="Import client list"
      section="Administration"
      description={
        <>
          Upload the client list workbook. You see what it would add, then confirm: new clients are
          created and linked to their consultants, and existing clients are only filled in where
          details are missing. ID numbers are always shown masked.
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
            <History key={historyKey} onOpen={(id) => void open(id)} />
          </>
        )}
      </div>
    </ListPage>
  );
}
