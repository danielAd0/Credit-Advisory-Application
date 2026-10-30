import type { ReactNode } from 'react';
import type { Role } from '../api';
import { hasAnyRole, useSession } from './useAuth';

/** Hides a page from roles that may not use it. The backend checks again on every request. */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const session = useSession();

  if (!hasAnyRole(session, roles)) {
    return (
      <div className="panel">
        <h1>No access</h1>
        <p className="muted">Your role cannot open this page.</p>
      </div>
    );
  }
  return <>{children}</>;
}
