/**
 * Guest navigation bar — public, auth-aware, no sidebar.
 * Signed-in ADMINS get a direct "Open dashboard" link; signed-in CUSTOMERS
 * get "My account" (change password) — customers live on the guest surface.
 * Anonymous guests get Sign in / Create account.
 */

import { Link, NavLink } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { loadGuestPrefRecord } from '../../guest/preferenceStore';
import type { GuestPrefRecord } from '../../guest/types';

interface GuestNavProps {
  onEditPreferences: () => void;
  prefRecord: GuestPrefRecord | null;
}

export default function GuestNav({ onEditPreferences, prefRecord }: GuestNavProps): JSX.Element {
  const { token, isAdmin } = useAuth();
  const hasPrefs = prefRecord?.status === 'saved';

  return (
    <header className="gnav">
      <div className="gnav-inner">
        <Link to="/" className="gnav-brand" aria-label="Brew Haven home">
          <span className="gnav-logo" aria-hidden="true">☕</span>
          <span>Brew Haven</span>
        </Link>
        <nav className="gnav-links" aria-label="Guest navigation">
          <NavLink to="/" end className={({ isActive }) => (isActive ? 'gnav-link gnav-link-active' : 'gnav-link')}>
            Home
          </NavLink>
          <NavLink to="/menu" className={({ isActive }) => (isActive ? 'gnav-link gnav-link-active' : 'gnav-link')}>
            Menu
          </NavLink>
          <button
            type="button"
            className="gnav-link gnav-link-button"
            onClick={onEditPreferences}
          >
            {hasPrefs ? 'Edit preferences' : 'Personalize my picks'}
          </button>
        </nav>
        <div className="gnav-auth">
          {token ? (
            isAdmin ? (
              <Link to="/dashboard" className="btn btn-primary gnav-cta">
                Open dashboard
              </Link>
            ) : (
              <Link to="/change-password" className="btn btn-outline gnav-cta">
                My account
              </Link>
            )
          ) : (
            <>
              <Link to="/login" className="btn btn-outline gnav-cta">
                Sign in
              </Link>
              <Link to="/signup" className="btn btn-primary gnav-cta gnav-cta-signup">
                Create account
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}

/** Re-exported for pages that need to re-read the record after store changes. */
export function readPrefRecord(): GuestPrefRecord | null {
  return loadGuestPrefRecord();
}
