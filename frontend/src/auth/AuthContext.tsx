import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  clearToken, decodeJwtEmail, decodeJwtRole, getToken, registerSessionExpiredHandler, setToken,
} from '../api/client';
import * as api from '../api/cafe';

export interface AuthState {
  token: string | null;
  email: string | null;
  role: 'admin' | 'user' | null;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: (options?: { expired?: boolean }) => void;
}

export const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  const navigate = useNavigate();
  const [token, setTokenState] = useState<string | null>(() => getToken());
  const [expired, setExpired] = useState(false);

  const role = useMemo(() => (token ? decodeJwtRole(token) : null), [token]);
  const email = useMemo(() => (token ? decodeJwtEmail(token) : null), [token]);

  const logout = useCallback(
    (options?: { expired?: boolean }) => {
      clearToken();
      setTokenState(null);
      setExpired(!!options?.expired);
      navigate('/login', { replace: true });
    },
    [navigate],
  );

  useEffect(() => {
    // Any 401/403 on a protected API call (e.g. JWT expiry after 10h) ends the session.
    registerSessionExpiredHandler(() => {
      if (getToken()) {
        clearToken();
        setTokenState(null);
        setExpired(true);
        navigate('/login', { replace: true });
      }
    });
  }, [navigate]);

  const login = useCallback(
    async (loginEmail: string, password: string) => {
      const result = await api.login(loginEmail, password); // throws ApiError with backend message
      if (!result?.token) {
        throw new Error('Login succeeded but no token was returned.');
      }
      setToken(result.token);
      setTokenState(result.token);
      setExpired(false);
    },
    [],
  );

  const value: AuthState = {
    token,
    email,
    role,
    isAdmin: role === 'admin',
    login,
    logout,
  };

  return (
    <AuthContext.Provider value={value}>
      {expired && (
        <div className="session-banner" role="status">
          Your session has expired. Please sign in again.
          <button type="button" className="session-banner-close" onClick={() => setExpired(false)} aria-label="Dismiss">
            ×
          </button>
        </div>
      )}
      {children}
    </AuthContext.Provider>
  );
}

// useAuth lives in ./useAuth.ts (separate module keeps this file Fast-Refresh compatible).
