import { useEffect, useState } from 'react';
import {
  workbookCheck,
  type PageResponse,
  type WorkbookCheckFigure,
  type WorkbookCheckSummary,
} from '../api';
import Pager from '../components/Pager';
import './imports.css';
import './workbookcheck.css';

const rand = (n: number) =>
  n.toLocaleString('en-ZA', { style: 'currency', currency: 'ZAR', minimumFractionDigits: 2 });

const SECTION_LABEL: Record<string, string> = {
  DAILY_BANKED: 'Daily banked',
  DAILY_QUOTATION: 'Daily quotation',
  DAILY_TARGET: 'Daily target',
  DAILY_CLIENT_COUNT: 'Daily client count',
  CONSULTANT_PRODUCT: 'Consultant products',
  CONSULTANT_CLIENT_COUNT: 'Consultant client count',
  CONSULTANT_QUOTATION: 'Consultant quotation',
  CONSULTANT_TOTAL: 'Consultant totals',
  FINANCE: 'Finance',
  SHEET_DIFFERENCE: 'Sheet difference',
  BUDGET_BY_STRUCTURE: 'Budget by structure',
  BREAKDOWN: 'Breakdown',
  BREAKDOWN_SUMMARY: 'Breakdown summary',
  OTHER: 'Other',
};

const sectionName = (code: string) => SECTION_LABEL[code] ?? code;

function Stat({ value, label, bad }: { value: number; label: string; bad?: boolean }) {
  return (
    <div className={`wi-stat${bad && value > 0 ? ' bad' : ''}`}>
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

const shown = (f: WorkbookCheckFigure) =>
  f.sheetError ?? f.sheetText ?? (f.sheetValue === null ? '-' : rand(f.sheetValue));

const correct = (f: WorkbookCheckFigure) =>
  f.computedText ?? (f.computedValue === null ? '-' : rand(f.computedValue));

function Figures({ id, summary }: { id: number; summary: WorkbookCheckSummary }) {
  const [onlyWrong, setOnlyWrong] = useState(true);
  const [defect, setDefect] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<WorkbookCheckFigure> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    workbookCheck
      .figures(id, { onlyDifferences: onlyWrong, defect: defect || undefined, page, size: 25 })
      .then((r) => live && (setResult(r), setError(null)))
      .catch(() => live && setError('Could not load the figures.'));
    return () => {
      live = false;
    };
  }, [id, onlyWrong, defect, page]);

  return (
    <div className="wc-figures">
      <div className="wc-filters">
        <label>
          <input
            type="checkbox"
            checked={onlyWrong}
            onChange={(e) => {
              setOnlyWrong(e.target.checked);
              setPage(0);
            }}
          />{' '}
          Only figures that are wrong this week
        </label>
        <select
          aria-label="Filter by what is wrong"
          value={defect}
          onChange={(e) => {
            setDefect(e.target.value);
            setPage(0);
          }}
        >
          <option value="">Every kind of mistake</option>
          {summary.defects.map((d) => (
            <option key={d.defectCode} value={d.defectCode}>
              {d.description}
            </option>
          ))}
        </select>
      </div>

      {error && <p className="alert error">{error}</p>}
      {!error && result === null && <p className="muted">Loading...</p>}
      {result && result.items.length === 0 && <p className="muted">Nothing to show.</p>}

      {result && result.items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Cell</th>
                <th>What it is</th>
                <th>Spreadsheet shows</th>
                <th>Correct figure</th>
                <th>Difference</th>
                <th>What is wrong</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((f) => (
                <tr key={f.id}>
                  <td>
                    {f.sheetName} {f.cellRef}
                  </td>
                  <td className="wc-wrap">
                    {sectionName(f.section)}
                    {f.rowLabel ? `, ${f.rowLabel}` : ''}
                    {f.columnLabel ? `, ${f.columnLabel}` : ''}
                  </td>
                  <td>{shown(f)}</td>
                  <td>{correct(f)}</td>
                  <td className={f.matches === false ? 'wc-diff' : undefined}>
                    {f.difference === null ? '-' : rand(f.difference)}
                  </td>
                  <td className="wc-wrap">
                    {f.defectDescription ?? (f.matches === false ? 'Not explained' : '-')}
                    {f.formula && <code className="wc-formula">{f.formula}</code>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {result && (
        <Pager
          page={result.page}
          size={result.size}
          totalItems={result.totalItems}
          totalPages={result.totalPages}
          onPage={setPage}
        />
      )}
    </div>
  );
}

export default function WorkbookCheck({ id }: { id: number }) {
  const [summary, setSummary] = useState<WorkbookCheckSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showFigures, setShowFigures] = useState(false);

  useEffect(() => {
    let live = true;
    workbookCheck
      .summary(id)
      .then((r) => live && (setSummary(r), setError(null)))
      .catch(() => live && setError('Could not load the workbook check.'));
    return () => {
      live = false;
    };
  }, [id]);

  return (
    <section className="wi-card" aria-label="Workbook check">
      <h2>Workbook check</h2>
      <p className="muted">
        The app always uses figures recalculated from the client rows. This compares them with the
        figures the workbook&apos;s own Report and Breakdown sheets show, so you can see where its
        formulas were wrong. It never stops an import.
      </p>

      {error && <p className="alert error">{error}</p>}
      {!error && summary === null && <p className="muted">Loading...</p>}

      {summary?.status === 'NOT_CHECKED' && (
        <p className="alert">
          This file has no Report or Breakdown sheet, so there was nothing to check.
        </p>
      )}

      {summary?.status === 'MATCHED' && (
        <p className="alert">
          Every figure on the Report and Breakdown sheets matches the recalculated figure (
          {summary.compared} checked).
        </p>
      )}

      {summary?.status === 'DIFFERENCES' && (
        <>
          <div className="wi-stats">
            <Stat value={summary.figures} label="Figures read" />
            <Stat value={summary.compared} label="Figures checked" />
            <Stat value={summary.differences} label="Wrong this week" bad />
          </div>

          <details>
            <summary>What is wrong in the workbook ({summary.defects.length})</summary>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Mistake</th>
                    <th>Formulas affected</th>
                    <th>Wrong this week</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.defects.map((d) => (
                    <tr key={d.defectCode}>
                      <td className="wc-wrap">{d.description}</td>
                      <td>{d.figures}</td>
                      <td>{d.differences}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>

          <details>
            <summary>Where the differences are ({summary.sections.length})</summary>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Sheet</th>
                    <th>Part</th>
                    <th>Figures</th>
                    <th>Checked</th>
                    <th>Wrong this week</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.sections.map((s) => (
                    <tr key={`${s.sheetName}-${s.section}`}>
                      <td>{s.sheetName}</td>
                      <td>{sectionName(s.section)}</td>
                      <td>{s.figures}</td>
                      <td>{s.compared}</td>
                      <td>{s.differences}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>

          <p>
            <button type="button" className="btn" onClick={() => setShowFigures((v) => !v)}>
              {showFigures ? 'Hide the figures' : 'Show the figures'}
            </button>
          </p>
          {showFigures && <Figures id={id} summary={summary} />}
        </>
      )}
    </section>
  );
}
