import { useEffect, useState } from 'react';
import {
  detailOf,
  imports,
  type ImportHistoryItem,
  type ImportSummary,
  type PageResponse,
} from '../api';
import { useSession } from '../auth/useAuth';
import ListPage from '../components/ListPage';
import Pager from '../components/Pager';
import './addclient.css';
import './consultants.css';
import './imports.css';

const SIZE = 10;

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', minimumFractionDigits: 2 });
const day = (iso: string) => new Date(iso).toLocaleDateString('en-ZA');
const stamp = (iso: string | null) => (iso ? new Date(iso).toLocaleString('en-ZA') : '-');

const STATE_LABEL: Partial<Record<string, string>> = {
  PREVIEWED: 'Waiting for confirmation',
  COMMITTED: 'Imported',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

function readSeen(storeKey: string): number {
  try {
    const value = Number(localStorage.getItem(storeKey));
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
}

function saveSeen(storeKey: string, id: number) {
  try {
    localStorage.setItem(storeKey, String(id));
  } catch {
    return;
  }
}

function UploadSummary({ id, onBack }: { id: number; onBack: () => void }) {
  const [job, setJob] = useState<ImportSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    imports
      .get(id)
      .then((r) => live && setJob(r))
      .catch((err: unknown) => live && setError(detailOf(err) ?? 'Could not load this upload.'));
    return () => {
      live = false;
    };
  }, [id]);

  return (
    <section className="wi-card" aria-label="Upload summary">
      <button type="button" className="btn" onClick={onBack}>
        Back to uploads
      </button>
      {error && <p className="alert error">{error}</p>}
      {!error && job === null && <p className="muted">Loading...</p>}
      {job && (
        <>
          <h2>Week of {day(job.weekStarting)}</h2>
          <p className="muted">
            {job.fileName} · {STATE_LABEL[job.state] ?? job.state} · uploaded by {job.uploadedBy} on{' '}
            {stamp(job.createdAt)}
            {job.committedAt && ` · imported ${stamp(job.committedAt)}`}
          </p>
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
          <p className="muted">
            Only the admin can open the rows and issues of an upload or run an import.
          </p>
        </>
      )}
    </section>
  );
}

export default function UploadsPage() {
  const session = useSession();
  const storeKey = `ca.uploadsSeen.${session.email}`;
  const [seenBefore] = useState(() => readSeen(storeKey));
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<ImportHistoryItem> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<number | null>(null);

  useEffect(() => {
    let live = true;
    imports
      .list(page, SIZE)
      .then((r) => {
        if (!live) return;
        setResult(r);
        if (page === 0) {
          const newest = Math.max(
            0,
            ...r.items.filter((i) => i.state === 'COMMITTED').map((i) => i.id),
          );
          if (newest > readSeen(storeKey)) saveSeen(storeKey, newest);
        }
      })
      .catch(() => live && setError('Could not load the uploads.'));
    return () => {
      live = false;
    };
  }, [page, storeKey]);

  return (
    <ListPage
      title="Uploads"
      section="Administration"
      description="See when the admin has uploaded and imported a weekly register. You can look, but only the admin can run an import."
      footer="Confidential staff information"
    >
      {openId !== null ? (
        <UploadSummary id={openId} onBack={() => setOpenId(null)} />
      ) : (
        <section className="wi-card" aria-label="Uploads">
          {error && <p className="alert error">{error}</p>}
          {!error && result === null && <p className="muted">Loading...</p>}
          {result && result.items.length === 0 && (
            <p className="muted">Nothing has been uploaded yet.</p>
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
                      <th>Uploaded</th>
                      <th>Imported</th>
                      <th>
                        <span className="sr-only">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.items.map((h) => (
                      <tr key={h.id}>
                        <td className="lp-strong">
                          {day(h.weekStarting)}
                          {seenBefore > 0 && h.state === 'COMMITTED' && h.id > seenBefore && (
                            <span className="badge active"> New</span>
                          )}
                        </td>
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
                        <td>{stamp(h.createdAt)}</td>
                        <td>{stamp(h.committedAt)}</td>
                        <td>
                          <button
                            type="button"
                            className="btn lp-row-btn"
                            onClick={() => setOpenId(h.id)}
                          >
                            View
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
      )}
    </ListPage>
  );
}
