import QRCode from 'qrcode';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { detailOf, mfa, mfaPending, type MfaSetup } from '../api';
import { useAuth, useSession } from '../auth/useAuth';
import './twostep.css';

/**
 * The second sign-in step (contract section 2, "Two-step sign-in").
 *  - First time: set up an authenticator app (QR code), confirm with its code, save the
 *    recovery codes (shown once).
 *  - Every later sign-in: the 6-digit code, or a recovery code if the phone is lost.
 * Five wrong codes lock the account for 15 minutes and sign this browser out, like passwords.
 */
type Mode = 'start' | 'scan' | 'code' | 'recovery' | 'saved-codes';

/** "123 456" or "123-456" -> "123456"; at most 6 digits. */
const digitsOnly = (value: string) => value.replace(/\D/g, '').slice(0, 6);

/** "JBSWY3DPEHPK3PXP" -> "JBSW Y3DP EHPK 3PXP", easier to type into the app by hand. */
const grouped = (secret: string) => secret.replace(/(.{4})(?=.)/g, '$1 ');

export default function TwoStepPage() {
  const session = useSession();
  const { refreshSession, signOut } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>(session.mfa === 'SETUP_REQUIRED' ? 'start' : 'code');
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [recoveryCode, setRecoveryCode] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    document.title = 'Two-step sign-in - Credit Advisory';
  }, []);

  useEffect(() => {
    input.current?.focus();
  }, [mode]);

  // Done (and not still showing the recovery codes): carry on into the app.
  if (!mfaPending(session) && codes === null) return <Navigate to="/" replace />;

  function failed(err: unknown, fallback: string) {
    setError(detailOf(err) ?? fallback);
    setCode('');
    setRecoveryCode('');
    setBusy(false);
    input.current?.focus();
  }

  async function startSetup() {
    setBusy(true);
    setError(null);
    try {
      const started = await mfa.setup();
      setSetup(started);
      setQr(await QRCode.toDataURL(started.otpauthUri, { margin: 1, width: 220 }));
      setMode('scan');
      setBusy(false);
    } catch (err) {
      failed(err, 'Could not start the set-up. Please try again.');
    }
  }

  async function confirmSetup(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (code.length !== 6) {
      setError('Enter the 6 digits your authenticator app shows.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const enabled = await mfa.enable(code);
      // Do NOT refresh the session yet: the codes must be shown first, and only once.
      setCodes(enabled.recoveryCodes);
      setMode('saved-codes');
      setBusy(false);
    } catch (err) {
      failed(err, 'The code is not correct. Enter the code your app shows now.');
    }
  }

  async function verify(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (code.length !== 6) {
      setError('Enter the 6 digits your authenticator app shows.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await mfa.verify(code);
      await refreshSession();
      navigate('/', { replace: true });
    } catch (err) {
      failed(err, 'The code is not correct. Enter the code your app shows now.');
    }
  }

  async function recover(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (!recoveryCode.trim()) {
      setError('Enter one of your recovery codes.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await mfa.recover(recoveryCode.trim());
      await refreshSession();
      navigate('/', { replace: true });
    } catch (err) {
      failed(err, 'That recovery code is not correct, or it was already used.');
    }
  }

  async function finish() {
    setBusy(true);
    await refreshSession();
    navigate('/', { replace: true });
  }

  async function copyCodes() {
    if (!codes) return;
    try {
      await navigator.clipboard.writeText(codes.join('\n'));
      setNotice('Copied. Paste them somewhere safe, then clear your clipboard.');
    } catch {
      setNotice('Could not copy. Download them or write them down instead.');
    }
  }

  function downloadCodes() {
    if (!codes) return;
    const text = [
      'Credit Advisory - recovery codes',
      `Account: ${session.email}`,
      'Each code works once, instead of the authenticator app code.',
      '',
      ...codes,
      '',
    ].join('\r\n');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'credit-advisory-recovery-codes.txt';
    link.click();
    URL.revokeObjectURL(url);
    setNotice('Downloaded. Keep the file somewhere safe, not on a shared computer.');
  }

  const errorBox = error && (
    <div className="alert error" role="alert">
      {error}
    </div>
  );

  const signOutButton = (
    <button type="button" className="btn" onClick={() => void signOut()} disabled={busy}>
      Sign out
    </button>
  );

  return (
    <main className="ts">
      <div className="ts-card">
        <img className="ts-logo" src="/logo.png" alt="" />

        {mode === 'start' && (
          <>
            <h1>Set up two-step sign-in</h1>
            <p className="muted">
              Client records are private, so every sign-in needs your password and a code from your
              phone. This takes about two minutes.
            </p>
            <ol className="ts-steps">
              <li>
                Install <strong>Microsoft Authenticator</strong> or{' '}
                <strong>Google Authenticator</strong> on your phone (free, from the app store).
              </li>
              <li>Press the button below and scan the QR code with the app.</li>
              <li>Type the 6-digit code the app shows.</li>
            </ol>
            {errorBox}
            <div className="ts-actions">
              <button
                type="button"
                className="btn primary"
                onClick={() => void startSetup()}
                disabled={busy}
              >
                {busy ? 'Starting...' : 'Set up my authenticator app'}
              </button>
              {signOutButton}
            </div>
          </>
        )}

        {mode === 'scan' && setup && (
          <form onSubmit={confirmSetup} noValidate>
            <h1>Scan this code</h1>
            <p className="muted">
              In the app, choose <em>Add account</em> (or the + button), then scan the QR code.
            </p>
            {qr && (
              <img className="ts-qr" src={qr} width={220} height={220} alt="QR code to scan" />
            )}
            <details className="ts-manual">
              <summary>Cannot scan? Type this key into the app instead</summary>
              <code className="ts-secret">{grouped(setup.secret)}</code>
              <p className="ts-hint">Choose a time-based key. Never share this key with anyone.</p>
            </details>
            {errorBox}
            <div className="ts-field">
              <label htmlFor="ts-code">6-digit code from the app</label>
              <input
                id="ts-code"
                ref={input}
                className="ts-code-input"
                inputMode="numeric"
                autoComplete="one-time-code"
                value={code}
                onChange={(e) => setCode(digitsOnly(e.target.value))}
              />
            </div>
            <div className="ts-actions">
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Checking...' : 'Confirm'}
              </button>
              {signOutButton}
            </div>
          </form>
        )}

        {mode === 'saved-codes' && codes && (
          <>
            <h1>Save your recovery codes</h1>
            <p className="muted">
              If you lose your phone, each of these codes lets you sign in <strong>once</strong>{' '}
              instead of the app code. This is the only time they are shown.
            </p>
            <ol className="ts-codes">
              {codes.map((c) => (
                <li key={c}>
                  <code>{c}</code>
                </li>
              ))}
            </ol>
            {notice && <p className="ts-notice">{notice}</p>}
            <div className="ts-actions">
              <button type="button" className="btn" onClick={() => void copyCodes()}>
                Copy
              </button>
              <button type="button" className="btn" onClick={downloadCodes}>
                Download
              </button>
            </div>
            <label className="ts-check">
              <input type="checkbox" checked={saved} onChange={(e) => setSaved(e.target.checked)} />{' '}
              I have saved my recovery codes somewhere safe
            </label>
            <div className="ts-actions">
              <button
                type="button"
                className="btn primary"
                onClick={() => void finish()}
                disabled={!saved || busy}
              >
                Continue
              </button>
            </div>
          </>
        )}

        {mode === 'code' && (
          <form onSubmit={verify} noValidate>
            <h1>Enter your code</h1>
            <p className="muted">
              Open your authenticator app and type the 6-digit code for {session.email}.
            </p>
            {errorBox}
            <div className="ts-field">
              <label htmlFor="ts-code">6-digit code</label>
              <input
                id="ts-code"
                ref={input}
                className="ts-code-input"
                inputMode="numeric"
                autoComplete="one-time-code"
                value={code}
                onChange={(e) => setCode(digitsOnly(e.target.value))}
              />
              <p className="ts-hint">
                After 5 wrong codes you are signed out and the account is locked for 15 minutes.
              </p>
            </div>
            <div className="ts-actions">
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Checking...' : 'Sign in'}
              </button>
              {signOutButton}
            </div>
            <button
              type="button"
              className="ts-link"
              onClick={() => {
                setError(null);
                setMode('recovery');
              }}
            >
              Lost your phone? Use a recovery code
            </button>
          </form>
        )}

        {mode === 'recovery' && (
          <form onSubmit={recover} noValidate>
            <h1>Use a recovery code</h1>
            <p className="muted">
              Type one of the codes you saved when you set up the app, for example K7QM2-XW4PD. Each
              code works once. No codes left? Ask an administrator to reset your two-step sign-in.
            </p>
            {errorBox}
            <div className="ts-field">
              <label htmlFor="ts-recovery">Recovery code</label>
              <input
                id="ts-recovery"
                ref={input}
                className="ts-code-input"
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                maxLength={20}
                value={recoveryCode}
                onChange={(e) => setRecoveryCode(e.target.value)}
              />
            </div>
            <div className="ts-actions">
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Checking...' : 'Sign in'}
              </button>
              {signOutButton}
            </div>
            <button
              type="button"
              className="ts-link"
              onClick={() => {
                setError(null);
                setMode('code');
              }}
            >
              Back to the app code
            </button>
          </form>
        )}
      </div>
    </main>
  );
}
