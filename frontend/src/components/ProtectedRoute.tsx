import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';

/**
 * Route guard: unauthenticated users are redirected to /login with a "from"
 * location so a successful login returns them to where they wanted to go.
 */
export default function ProtectedRoute({ children }: { children: JSX.Element }): JSX.Element {
  const { token } = useAuth();
  const location = useLocation();
  if (!token) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  return children;
}
