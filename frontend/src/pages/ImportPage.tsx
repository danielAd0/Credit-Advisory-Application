import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from 'react';
import {
  ApiError,
  data,
  type ImportIssueKind,
  type ImportPreview,
  type ImportResult,
} from '../api';
import './import.css';

// Placeholder until the real limit is agreed with IT. The server must enforce its own limit too.
const MAX_MB = 10;
const MAX_BYTES = MAX_MB * 1024 * 1024;

const KIND_LABEL: Record<ImportIssueKind, string> = {
  INVALID_ID: 'Invalid ID number',
  DUPLICATE_IN_FILE: 'Duplicate in file',
  UNKNOWN_CONSULTANT: 'Unknown consultant code',
  INACTIVE_CONSULTANT: 'Consultant not active',
  ASSIGNMENT_CONFLICT: 'Different consultant already assigned',
};

type Stage =
  | { name: 'pick' }
  | { name: 'checking' }
  | { name: 'review'; preview: ImportPreview }
  | { name: 'importing'; preview: ImportPreview }
  | { name: 'done'; preview: ImportPreview; result: ImportResult };

const STEPS = ['Choose file', 'Review', 'Done'];
const stepOf = (s: Stage['name']) =>
  s === 'pick' || s === 'checking' ? 0 : s === 'review' || s === 'importing' ? 1 : 2;

function checkFile(file: File): string | null {
  if (!file.name.toLowerCase().endsWith('.xlsx'))
    return 'Choose an Excel workbook that ends in .xlsx.';
  if (file.size === 0) return 'That file is empty.';
  if (file.size > MAX_BYTES) return `That file is larger than ${MAX_MB} MB.`;
  return null;
}

function explain(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 413) return 'That file is too large for the server.';
    if (err.status === 400 || err.status === 415 || err.status === 422)
      return 'The server could not read that file. Check that it is an .xlsx workbook with the agreed worksheet.';
    if (err.status === 403) return 'You do not have permission to import.';
    if (err.status === 409 || err.status === 404)
      return 'This preview is out of date. Choose the file again.';
  }
  return 'We could not complete that. Check your connection and try again.';
}

// Spreadsheet programs run text that starts with these characters as a formula.
const safeCell = (v: string) => (/^[=+\-@]/.test(v) ? `'${v}` : v);

function downloadIssues(preview: ImportPreview) {
  const rows = [
    ['Row', 'ID number (masked)', 'Problem', 'Detail'],
    ...preview.issues.map((i) => [String(i.row), i.idNumberMasked, KIND_LABEL[i.kind], i.message]),
  ];
  const csv = rows
    .map((r) => r.map((c) => `"${safeCell(c).replace(/"/g, '""')}"`).join(','))
    .join('\r\n');
  const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'import-skipped-rows.csv';
  a.click();
  URL.revokeObjectURL(url);
}

export default function ImportPage() {
  const [stage, setStage] = useState<Stage>({ name: 'pick' });
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [filter, setFilter] = useState<ImportIssueKind | 'ALL'>('ALL');
  const headingRef = useRef<HTMLHeadingElement>(null);

  // Move keyboard and screen-reader focus to the new step's heading.
  useEffect(() => {
    headingRef.current?.focus();
  }, [stage.name]);

  async function handleFile(file: File | undefined) {
    if (!file) return;
    const problem = checkFile(file);
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    setStage({ name: 'checking' });
    try {
      const preview = await data.previewImport(file);
      setFilter('ALL');
      setStage({ name: 'review', preview });
    } catch (err) {
      setStage({ name: 'pick' });
      setError(explain(err));
    }
  }

  async function confirm(preview: ImportPreview) {
    setError(null);
    setStage({ name: 'importing', preview });
    try {
      const result = await data.confirmImport(preview.id);
      setStage({ name: 'done', preview, result });
    } catch (err) {
      setStage({ name: 'review', preview });
      setError(explain(err));
    }
  }

  function restart() {
    setError(null);
    setStage({ name: 'pick' });
  }

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // lets the same file be chosen again
    void handleFile(file);
  };
  const onDrop = (e: DragEvent<HTMLLabelElement>) => {
    e.preventDefault();
    setDragging(false);
    void handleFile(e.dataTransfer.files[0]);
  };

  const current = stepOf(stage.name);

  return (
    <div className="imp">
      <h1>Import clients</h1>
      <p className="muted">
        Add or update clients and their consultants from an Excel workbook. Nothing is saved until
        you confirm.
      </p>

      <ol className="imp-steps" aria-label="Import steps">
        {STEPS.map((label, i) => (
          <li
            key={label}
            className={i < current ? 'is-done' : i === current ? 'is-current' : ''}
            aria-current={i === current ? 'step' : undefined}
          >
            <span className="imp-step-num" aria-hidden="true">
              {i + 1}
            </span>
            {label}
          </li>
        ))}
      </ol>

      {error && (
        <p className="alert error imp-error" role="alert">
          {error}
        </p>
      )}

      {(stage.name === 'pick' || stage.name === 'checking') && (
        <section className="imp-card">
          <h2 ref={headingRef} tabIndex={-1}>
            Choose a workbook
          </h2>
          <p className="muted">One .xlsx file with the agreed worksheet, up to {MAX_MB} MB.</p>

          {stage.name === 'checking' ? (
            <p className="imp-busy" role="status">
              <span className="imp-spinner" aria-hidden="true" />
              Checking the workbook. This can take a moment.
            </p>
          ) : (
            <label
              className={`imp-drop${dragging ? ' is-dragging' : ''}`}
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
            >
              <input
                className="imp-file"
                type="file"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={onPick}
              />
              <svg viewBox="0 0 24 24" width="32" height="32" aria-hidden="true">
                <path
                  d="M12 16V5m0 0-4 4m4-4 4 4M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.7"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              <span className="imp-drop-title">
                Drop the workbook here, or <u>browse</u>
              </span>
              <span className="imp-drop-note">.xlsx only</span>
            </label>
          )}

          <details className="imp-columns">
            <summary>Columns the workbook needs</summary>
            <ul>
              <li>
                <strong>ID Number</strong>: 13 digits, stored as text so a leading zero is kept
              </li>
              <li>
                <strong>First Name</strong> and <strong>Last Name</strong>
              </li>
              <li>
                <strong>Email</strong> and <strong>Phone</strong>: checked when filled in
              </li>
              <li>
                <strong>Consultant Code</strong>: how the consultant is matched
              </li>
              <li>
                <strong>Consultant Name</strong>: for display only
              </li>
            </ul>
            <p className="muted">
              These columns are provisional until the import template is approved.
            </p>
          </details>
        </section>
      )}

      {(stage.name === 'review' || stage.name === 'importing') &&
        (() => {
          const p = stage.preview;
          const importable = p.newRows + p.updatedRows;
          const skipped = p.invalidRows + p.conflictRows;
          const busy = stage.name === 'importing';
          const kinds = (Object.keys(KIND_LABEL) as ImportIssueKind[]).filter((k) =>
            p.issues.some((i) => i.kind === k),
          );
          const shown = filter === 'ALL' ? p.issues : p.issues.filter((i) => i.kind === filter);
          const stats = [
            { label: 'New clients', value: p.newRows, tone: 'good' },
            { label: 'Updated', value: p.updatedRows, tone: 'good' },
            { label: 'Unchanged', value: p.unchangedRows, tone: 'neutral' },
            { label: 'Invalid rows', value: p.invalidRows, tone: 'bad' },
            { label: 'Conflicts', value: p.conflictRows, tone: 'warn' },
          ];
          return (
            <>
              <section className="imp-card">
                <div className="imp-head">
                  <div>
                    <h2 ref={headingRef} tabIndex={-1}>
                      Review before importing
                    </h2>
                    <p className="muted">
                      <span className="mono">{p.fileName}</span> has {p.totalRows} rows.
                    </p>
                  </div>
                  <button className="btn" onClick={restart} disabled={busy}>
                    Choose another file
                  </button>
                </div>

                <ul className="imp-stats">
                  {stats.map((s) => (
                    <li key={s.label} className={`imp-stat ${s.tone}`}>
                      <span className="imp-stat-value">{s.value}</span>
                      <span className="imp-stat-label">{s.label}</span>
                    </li>
                  ))}
                </ul>

                <p className="imp-summary">
                  Importing adds <strong>{p.newRows}</strong> and updates{' '}
                  <strong>{p.updatedRows}</strong>.
                  {skipped > 0 ? (
                    <>
                      {' '}
                      The other <strong>{skipped}</strong> problem rows are skipped and listed
                      below. Conflicts are never applied automatically.
                    </>
                  ) : (
                    <> No problems were found.</>
                  )}
                </p>

                <div className="imp-actions">
                  <button
                    className="btn primary"
                    onClick={() => void confirm(p)}
                    disabled={busy || importable === 0}
                  >
                    {busy
                      ? 'Importing...'
                      : `Import ${importable} ${importable === 1 ? 'row' : 'rows'}`}
                  </button>
                  <button className="btn" onClick={restart} disabled={busy}>
                    Cancel
                  </button>
                </div>
              </section>

              {p.issues.length > 0 && (
                <section className="imp-card">
                  <h2>Rows that need attention</h2>
                  <div className="imp-filters" role="group" aria-label="Filter by problem">
                    <button
                      className={`imp-chip${filter === 'ALL' ? ' is-on' : ''}`}
                      aria-pressed={filter === 'ALL'}
                      onClick={() => setFilter('ALL')}
                    >
                      All ({p.issues.length})
                    </button>
                    {kinds.map((k) => (
                      <button
                        key={k}
                        className={`imp-chip${filter === k ? ' is-on' : ''}`}
                        aria-pressed={filter === k}
                        onClick={() => setFilter(k)}
                      >
                        {KIND_LABEL[k]} ({p.issues.filter((i) => i.kind === k).length})
                      </button>
                    ))}
                  </div>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th className="num">Row</th>
                          <th>ID number</th>
                          <th>Problem</th>
                          <th>What to do</th>
                        </tr>
                      </thead>
                      <tbody>
                        {shown.map((i) => (
                          <tr key={`${i.row}-${i.kind}`}>
                            <td className="num">{i.row}</td>
                            <td className="mono">{i.idNumberMasked}</td>
                            <td>{KIND_LABEL[i.kind]}</td>
                            <td className="imp-wrap">{i.message}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p className="muted imp-note">
                    ID numbers are masked here and in the downloaded list.
                  </p>
                </section>
              )}
            </>
          );
        })()}

      {stage.name === 'done' && (
        <section className="imp-card">
          <h2 ref={headingRef} tabIndex={-1}>
            Import finished
          </h2>
          <ul className="imp-stats">
            <li className="imp-stat good">
              <span className="imp-stat-value">{stage.result.imported}</span>
              <span className="imp-stat-label">Added or updated</span>
            </li>
            <li className="imp-stat neutral">
              <span className="imp-stat-value">{stage.result.unchanged}</span>
              <span className="imp-stat-label">Already up to date</span>
            </li>
            <li className="imp-stat bad">
              <span className="imp-stat-value">{stage.result.skipped}</span>
              <span className="imp-stat-label">Skipped</span>
            </li>
          </ul>
          <p className="imp-summary">
            {stage.result.skipped > 0
              ? 'Skipped rows were not changed. Download the list, fix them in the workbook, and upload it again. Rows that were already imported will not be duplicated.'
              : 'Every row was applied.'}
          </p>
          <div className="imp-actions">
            {stage.preview.issues.length > 0 && (
              <button className="btn" onClick={() => downloadIssues(stage.preview)}>
                Download skipped rows (CSV)
              </button>
            )}
            <button className="btn primary" onClick={restart}>
              Import another file
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
