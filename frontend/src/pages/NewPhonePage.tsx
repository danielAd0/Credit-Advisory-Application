import QRCode from 'qrcode';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ApiError,
  REAUTHENTICATION_REQUIRED,
  detailOf,
  idReveal,
  mfa,
  type MfaSetup,
} from '../api';
import { saveFile } from '../api/download';
import { useAuth, useSession } from '../auth/useAuth';
import './newphone.css';
import './twostep.css';

type Step = 'password' | 'scan' | 'codes';

const digitsOnly = (value: string) => value.replace(/\D/g, '').slice(0, 6);

const grouped = (secret: string) => secret.replace(/(.{4})(?=.)/g, '$1 ');

export default function NewPhonePage() {
  const session = useSession();
  const { refreshSession } = useAuth();
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('password');
  const [password, setPassword] = useState('');
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    input.current?.focus();
  }, [step]);

  async function checkPassword(e: FormEvent) {
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
      const started = await mfa.change();
      setQr(await QRCode.toDataURL(started.otpauthUri, { margin: 1, width: 220 }));
      setSetup(started);
      setStep('scan');
    } catch (err) {
      setError(detailOf(err) ?? 'Could not check your password. Please try again.');
    } finally {
      setPassword('');
      setBusy(false);
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (code.length !== 6) {
      setError('Enter the 6 digits your new phone shows.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const done = await mfa.confirmChange(code);
      setCodes(done.recoveryCodes);
      setStep('codes');
    } catch (err) {
      if (err instanceof ApiError && err.type === REAUTHENTICATION_REQUIRED) {
        setStep('password');
        setError('For your safety, type your password again. It was more than 5 minutes ago.');
      } else {
        setError(detailOf(err) ?? 'The code is not correct. Enter the code your new phone shows.');
      }
      setCode('');
    } finally {
      setBusy(false);
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
    saveFile(new Blob([text], { type: 'text/plain' }), 'credit-advisory-recovery-codes.txt');
    setNotice('Downloaded. Keep the file somewhere safe, not on a shared computer.');
  }

  const errorBox = error && (
    <div className="alert error" role="alert">
      {error}
    </div>
  );

  return (
    <div className="np">
      <h1>Move to a new phone</h1>
      <p className="muted">
        Use this when you have a new phone, or after you signed in with a recovery code because the
        old one was lost.
      </p>

      <div className="np-card">
        {step === 'password' && (
          <form onSubmit={(e) => void checkPassword(e)} noValidate>
            <h2>1. Confirm it is you</h2>
            <p className="muted">
              Type your password again. Your current authenticator app keeps working until the last
              step.
            </p>
            {errorBox}
            <div className="ts-field">
              <label htmlFor="np-password">Password</label>
              <input
                id="np-password"
                ref={input}
                className="np-password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            <div className="ts-actions">
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Checking...' : 'Continue'}
              </button>
            </div>
          </form>
        )}

        {step === 'scan' && setup && (
          <form onSubmit={(e) => void confirm(e)} noValidate>
            <h2>2. Scan with your new phone</h2>
            <p className="muted">
              In the authenticator app on the <strong>new</strong> phone, choose{' '}
              <em>Add account</em> (or the + button), then scan the QR code.
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
              <label htmlFor="np-code">6-digit code the new phone shows</label>
              <input
                id="np-code"
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
                {busy ? 'Checking...' : 'Move to this phone'}
              </button>
            </div>
          </form>
        )}

        {step === 'codes' && codes && (
          <>
            <h2>3. Save your new recovery codes</h2>
            <p className="muted">
              Your new phone is now the one that works. Your old recovery codes no longer work. Each
              of these codes lets you sign in <strong>once</strong> instead of the app code. This is
              the only time they are shown. You were signed out on your other devices.
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
              I have saved my new recovery codes somewhere safe
            </label>
            <div className="ts-actions">
              <button
                type="button"
                className="btn primary"
                onClick={() => void finish()}
                disabled={!saved || busy}
              >
                Done
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
