import { useContext } from 'react';
import { AuthContext, type AuthState } from './AuthContext';

/**
 * useAuth — access the authenticated session. Kept in a separate module from the
 * <AuthProvider> component so Vite React Fast Refresh stays compatible (mixing a
 * hook export with the provider component caused dev-mode context invalidations).
 */
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
