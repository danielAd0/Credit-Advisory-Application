import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {Navigate , useNavigate , useSearchParams} from 'react-router-dom';
import { ApiError } from '../api';
import {useAuth} from '../auth/useAuth';
import LoginGauge from '../components/LoginGauge';
import './login.css';


const DEMO = import.meta.env.VITE_USE_MOCK === 'true';

export default function LoginPage(){

    const {session , signIn} = useAuth();
    const navigate = useNavigate();
    const [params] = useSearchParams();
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [capsOn, setCapsOn] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const passwordRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        document.title = 'Sign in - Credit Advisory';
    },[]);

    if (session) return <Navigate to="/" replace />;

    const checkCaps = (e: KeyboardEvent<HTMLInputElement>) => setCapsOn(e.getModifierState('CapsLock'));

    async function onSubmit(e : FormEvent){
        e.preventDefault();
        if(busy) return;
        setBusy(true);
        setError(null);

        try{
            await signIn(email.trim() , password);
            navigate('/' , {replace : true});
        }
        catch(err){
            setPassword('');
            setBusy(false);
            setError(
                err instanceof ApiError && err.status === 401
                ? 'Sign-in failed. Check your details , or try again later if your account is locked.'
                : 'We could not sign you in right now. Check your connection and try again.'
            );

            passwordRef.current?.focus();
        }
    }

    return(

        <div className="lg-layout">

            <aside className="lg-brand" aria-label="Credit Advisory">
                    <div className="lg-logo">
                        <img className="lg-logo-img" src="/logo.png" alt="" />
                        <span className="lg-logo-name">Credit Advisory</span>
                    </div>

                <div className="lg-pitch">
                    <h1>Know where every client stands.</h1>
                    <p>Client records , credit cases and handovers , kept together and kept private.</p>
                </div>

                <p className="lg-assurance">
                    <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                        <path d="M12 2.5 4.5 5.6v5.7c0 4.7 3.1 8.6 7.5 10.2 4.4-1.6 7.5-5.5 7.5-10.2V5.6L12 2.5Z" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
                        <path d="m8.8 12.2 2.3 2.3 4.2-4.6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                    Client data is encrypted and every sign-in is recorded.
                </p>

                 <LoginGauge />

            </aside>

            <main className="lg-signin">
                <div className="lg-inner">
          <h2>Sign in</h2>
          <p className="lg-lead">Enter your work email and password.</p>

          {error && <div className="lg-alert error" role="alert">{error}</div>}
          {!error && params.has('logout') && <div className="lg-alert info" role="status">You have been signed out.</div>}
          {DEMO && !error && (
            <div className="lg-alert demo">Demo mode: any email and password work. Use the password "wrong" to see the error.</div>
          )}

          <form onSubmit={onSubmit} noValidate>
            <div className="lg-field">
              <label htmlFor="email">Email</label>
              <div className="lg-control">
                <svg className="lg-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                  <circle cx="12" cy="8" r="3.6" fill="none" stroke="currentColor" strokeWidth="1.7" />
                  <path d="M4.6 20c.6-3.6 3.6-5.6 7.4-5.6s6.8 2 7.4 5.6" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
                </svg>
                <input
                  id="email" name="email" type="email" required maxLength={254} autoFocus
                  autoComplete="username" autoCapitalize="none" spellCheck={false}
                  value={email} onChange={(e) => setEmail(e.target.value)}
                />
              </div>
            </div>

            <div className="lg-field">
              <label htmlFor="password">Password</label>
              <div className="lg-control">
                <svg className="lg-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                  <rect x="5" y="10.5" width="14" height="9.5" rx="2.4" fill="none" stroke="currentColor" strokeWidth="1.7" />
                  <path d="M8.4 10.5V8a3.6 3.6 0 0 1 7.2 0v2.5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
                </svg>
                <input
                  id="password" name="password" ref={passwordRef} required maxLength={128}
                  type={showPassword ? 'text' : 'password'} autoComplete="current-password"
                  aria-describedby={capsOn ? 'caps-hint' : undefined}
                  value={password} onChange={(e) => setPassword(e.target.value)}
                  onKeyDown={checkCaps} onKeyUp={checkCaps} onBlur={() => setCapsOn(false)}
                />
                <button
                  type="button" className="lg-reveal"
                  aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword}
                  onClick={() => setShowPassword((v) => !v)}
                >
                  {showPassword ? (
                    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                      <path d="M4 4l16 16M9.9 5.8A9.6 9.6 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16 16 0 0 1-3.2 3.9M6.2 7.6A16 16 0 0 0 2.5 12S6 18.5 12 18.5c1.4 0 2.7-.3 3.8-.8" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  ) : (
                    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                      <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
                      <circle cx="12" cy="12" r="2.8" fill="none" stroke="currentColor" strokeWidth="1.7" />
                    </svg>
                  )}
                </button>
              </div>
              {capsOn && <p className="lg-hint" id="caps-hint" role="status">Caps Lock is on.</p>}
            </div>


            <button type="submit" className={`lg-primary${busy ? ' is-loading' : ''}`} disabled={busy || !email || !password} aria-busy={busy}>
              <span className="lg-label">Sign in</span>
              <span className="lg-spinner" aria-hidden="true" />
            </button>
            </form>

             <p className="lg-help">Forgot your password? Ask your administrator.</p>
            </div>
        </main>

        </div>

    );

}

