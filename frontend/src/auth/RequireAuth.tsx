import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { mfaPending } from '../api';
import { useAuth } from './useAuth';

export default function RequireAuth() {
  const { session } = useAuth();
  const { pathname } = useLocation();
  if (!session) return <Navigate to="/login" replace />;
  // Password right, authenticator code not entered yet: the two-step screen is the only one that
  // works (the backend refuses everything else with 403 auth:mfa-required). This comes first: a
  // user with an admin-set password proves who they are with MFA, then chooses their password.
  if (mfaPending(session)) {
    return pathname === '/two-step' ? <Outlet /> : <Navigate to="/two-step" replace />;
  }
  // After an admin sets a password, the only screen that works is "Choose your own password".
  if (session.mustChangePassword && pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <Outlet />;
}
