import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, REAUTHENTICATION_REQUIRED, detailOf, idReveal } from '../api';
import './reveal.css';

/** How long a full ID number stays on screen before it is masked again. */
const SHOW_FOR_MS = 60_000;

/** "9001014800089" -> "900101 4800 089": birth date, sequence, the rest. Easier to read out. */
const readable = (n: string) =>
  n.length === 13 ? `${n.slice(0, 6)} ${n.slice(6, 10)} ${n.slice(10)}` : n;

type Phase = 'masked' | 'password' | 'shown';

/**
 * The masked ID number with a "Show" button. The full number needs the password again
 * (once per 5 minutes), is limited to 20 per hour and is audited by the server
 * (contract section 4). It is only kept in this component, and masked again after a minute.
 */
export default function RevealIdNumber({ clientId, masked }: { clientId: number; masked: string }) {
  const [phase, setPhase] = useState<Phase>('masked');
  const [idNumber, setIdNumber] = useState<string | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const passwordInput = useRef<HTMLInputElement>(null);

  // Mask it again after a minute, and never leave it behind when the page changes.
  useEffect(() => {
    if (phase !== 'shown') return;
    const timer = setTimeout(() => {
      setIdNumber(null);
      setPhase('masked');
    }, SHOW_FOR_MS);
    return () => clearTimeout(timer);
  }, [phase]);

  useEffect(() => {
    if (phase === 'password') passwordInput.current?.focus();
  }, [phase]);

  async function reveal() {
    const number = await idReveal.revealIdNumber(clientId);
    setIdNumber(number);
    setPhase('shown');
  }

  async function show() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      // The server decides whether the password is still needed: just ask.
      await reveal();
    } catch (err) {
      if (err instanceof ApiError && err.type === REAUTHENTICATION_REQUIRED) {
        setPhase('password');
      } else {
        setError(detailOf(err) ?? 'Could not show the ID number. Try again.');
      }
    } finally {
      setBusy(false);
    }
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
      setPassword('');
      await reveal();
    } catch (err) {
      setPassword('');
      setError(detailOf(err) ?? 'Could not show the ID number. Try again.');
      passwordInput.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  function hide() {
    setIdNumber(null);
    setPassword('');
    setError(null);
    setPhase('masked');
  }

  return (
    <div className="rv">
      <p className="rv-line">
        <span className="muted">ID number</span>{' '}
        {phase === 'shown' && idNumber ? (
          <>
            <span className="mono rv-full">{readable(idNumber)}</span>{' '}
            <button type="button" className="rv-link" onClick={hide}>
              Hide
            </button>
          </>
        ) : (
          <>
            <span className="mono">{masked}</span>{' '}
            {phase === 'masked' && (
              <button type="button" className="rv-link" onClick={() => void show()} disabled={busy}>
                {busy ? 'Checking...' : 'Show'}
              </button>
            )}
          </>
        )}
      </p>
      {phase === 'shown' && (
        <p className="rv-note">Shown for one minute. Viewing it is recorded in the audit log.</p>
      )}

      {phase === 'password' && (
        <form className="rv-box" onSubmit={confirm} noValidate>
          <label htmlFor="rv-password">Enter your password to see the full ID number</label>
          <p className="rv-note">
            Asked again after 5 minutes. Every full ID number you view is recorded.
          </p>
          <div className="rv-row">
            <input
              id="rv-password"
              ref={passwordInput}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <button type="submit" className="btn primary" disabled={busy}>
              {busy ? 'Checking...' : 'Show'}
            </button>
            <button type="button" className="btn" onClick={hide} disabled={busy}>
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
