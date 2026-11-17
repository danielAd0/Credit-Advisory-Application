import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, REAUTHENTICATION_REQUIRED, detailOf, idReveal, type ExportFormat } from '../api';
import './reveal.css';

function waitText(seconds: number | null): string {
  if (seconds === null) return '';
  const minutes = Math.max(1, Math.ceil(seconds / 60));
  if (minutes >= 120) return ` Try again in about ${Math.ceil(minutes / 60)} hours.`;
  return minutes === 1
    ? ' Try again in about a minute.'
    : ` Try again in about ${minutes} minutes.`;
}

export default function ExportClientData({ clientId }: { clientId: number }) {
  const [asking, setAsking] = useState(false);
  const [format, setFormat] = useState<ExportFormat>('pdf');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAs, setSavedAs] = useState<string | null>(null);
  const passwordInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (asking) passwordInput.current?.focus();
  }, [asking]);

  function fail(err: unknown) {
    const detail = detailOf(err) ?? 'Could not export the client data. Try again.';
    setError(
      err instanceof ApiError && err.status === 429 ? detail + waitText(err.retryAfter) : detail,
    );
  }

  async function download(which: ExportFormat) {
    try {
      await idReveal.exportClientData(clientId, which);
      setAsking(false);
      setSavedAs(`client-${clientId}-data.${which}`);
    } catch (err) {
      if (err instanceof ApiError && err.type === REAUTHENTICATION_REQUIRED) {
        setAsking(true);
      } else {
        setAsking(false);
        fail(err);
      }
    }
  }

  async function start(which: ExportFormat) {
    if (busy) return;
    setFormat(which);
    setBusy(true);
    setError(null);
    setSavedAs(null);
    await download(which);
    setBusy(false);
  }

  async function confirm(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (!password) {
      setError('Enter your password.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await idReveal.confirmPassword(password);
    } catch (err) {
      setPassword('');
      fail(err);
      setBusy(false);
      passwordInput.current?.focus();
      return;
    }
    setPassword('');
    await download(format);
    setBusy(false);
  }

  function cancel() {
    setPassword('');
    setError(null);
    setAsking(false);
  }

  return (
    <div className="rv ecd">
      {!asking && (
        <div className="ecd-buttons">
          <button type="button" className="btn" onClick={() => void start('pdf')} disabled={busy}>
            {busy && format === 'pdf' ? 'Preparing the file...' : 'Export client data as PDF'}
          </button>
          <button type="button" className="btn" onClick={() => void start('csv')} disabled={busy}>
            {busy && format === 'csv' ? 'Preparing the file...' : 'Export client data as CSV'}
          </button>
        </div>
      )}
      {!asking && !savedAs && (
        <p className="rv-note">
          For a POPIA access request: downloads everything held about this client, including the
          full ID number. The PDF is for reading, the CSV is for a spreadsheet.
        </p>
      )}
      {savedAs && (
        <p className="rv-note" role="status">
          Saved as {savedAs}. It holds the full ID number: hand it over as the POPIA procedure says
          and keep no copies. The export is recorded in the audit log.
        </p>
      )}

      {asking && (
        <form className="rv-box" onSubmit={confirm} noValidate>
          <label htmlFor="ecd-password">Enter your password to export this client's data</label>
          <p className="rv-note">
            Asked again after 5 minutes. The file counts as one full ID number toward your hourly
            limit and is recorded.
          </p>
          <div className="rv-row">
            <input
              id="ecd-password"
              ref={passwordInput}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <button type="submit" className="btn primary" disabled={busy}>
              {busy ? 'Checking...' : `Export ${format.toUpperCase()}`}
            </button>
            <button type="button" className="btn" onClick={cancel} disabled={busy}>
              Cancel
            </button>
          </div>
        </form>
      )}

      {error && (
        <p className="alert error rv-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
