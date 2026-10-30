import type {ReactNode} from 'react';
import type {Role} from '../api';
import {useSession} from './AuthContext';

export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
    const session = useSession();

    if(!roles.includes(session.role)){
        return(
          <div className="panel">
              <h1>No access</h1>
              <p className="muted">Your role cannot open this page.</p>
          </div>
        );}
         return <>{children}</>;

      }
