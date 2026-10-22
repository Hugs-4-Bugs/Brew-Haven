import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { postLoginDestination } from '../auth/postLoginDestination';
import { decodeJwtRole, getToken } from '../api/client';
import { Button } from '../components/ui';

/**
 * Login — surfaces the backend's exact contract messages:
 *  - "Wait for admin approval." (account not yet approved / disabled)
 *  - "Bad Credential." (wrong email/password)
 *  - Network errors ("Cannot reach the server…")
 */
export default function LoginPage(): JSX.Element {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation() as { state?: { from?: string } };
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!email.trim() || !password) {
      setError('Please enter your email and password.');
      return;
    }
    setLoading(true);
    try {
      await login(email.trim(), password);
      // Role-aware landing: admins open the management dashboard, normal
      // customers open the guest (restaurant) experience.
      const role = getToken() ? decodeJwtRole(getToken() as string) : null;
      navigate(postLoginDestination(role, location.state?.from), { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-wrap">
      <div className="card auth-card">
        <div className="auth-logo" aria-hidden="true">☕</div>
        <h1 className="auth-title">Cafe Management System</h1>
        <p className="auth-sub">Sign in to your account</p>
        <form onSubmit={onSubmit} noValidate>
          <div className="field">
            <label htmlFor="login-email">Email</label>
            <input
              id="login-email"
              className="input"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@cafe.local"
              required
            />
          </div>
          <div className="field">
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              className="input"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              required
            />
          </div>
          {error && (
            <p className="field-error" role="alert" style={{ marginBottom: 12 }}>
              {error}
            </p>
          )}
          <Button variant="primary" type="submit" loading={loading} style={{ width: '100%' }}>
            Sign in
          </Button>
        </form>
        <div className="auth-alt">
          <Link to="/forgot-password">Forgot password?</Link>
          <span style={{ margin: '0 8px' }}>·</span>
          New here? <Link to="/signup">Create an account</Link>
        </div>
      </div>
    </div>
  );
}
