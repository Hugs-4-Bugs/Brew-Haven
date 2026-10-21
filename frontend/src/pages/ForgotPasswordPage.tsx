import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import * as api from '../api/cafe';
import { Button } from '../components/ui';

/**
 * Forgot password — backend contract: a one-time temporary password is emailed to the
 * registered address (the API response is intentionally generic for unknown emails to
 * prevent account enumeration). Requires working SMTP configuration on the server.
 */
export default function ForgotPasswordPage(): JSX.Element {
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setInfo(null);
    if (!email.trim()) {
      setError('Please enter your email address.');
      return;
    }
    setLoading(true);
    try {
      await api.forgotPassword(email.trim());
      setInfo('If that email is registered, a temporary password has been sent to it. Please check your mailbox.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Request failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-wrap">
      <div className="card auth-card">
        <div className="auth-logo" aria-hidden="true">🔑</div>
        <h1 className="auth-title">Forgot password</h1>
        <p className="auth-sub">We will email you a temporary password</p>
        <form onSubmit={onSubmit} noValidate>
          <div className="field">
            <label htmlFor="fp-email">Registered email</label>
            <input
              id="fp-email"
              className="input"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          {error && (
            <p className="field-error" role="alert" style={{ marginBottom: 12 }}>
              {error}
            </p>
          )}
          {info && (
            <p style={{ color: 'var(--success)', marginBottom: 12, fontSize: 13.5 }} role="status">
              {info}
            </p>
          )}
          <Button variant="primary" type="submit" loading={loading} style={{ width: '100%' }}>
            Send temporary password
          </Button>
        </form>
        <div className="auth-alt">
          <Link to="/login">Back to sign in</Link>
        </div>
      </div>
    </div>
  );
}
