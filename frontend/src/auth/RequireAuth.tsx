import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from './useAuth';

export default function RequireAuth() {
  const { session } = useAuth();
  if (!session) return <Navigate to="/login" replace />;
  return <Outlet />;
}
