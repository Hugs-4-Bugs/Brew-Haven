import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import * as api from '../api/cafe';
import { Button } from '../components/ui';

/**
 * Signup — creates a role="user" account. Normal customers are ACTIVE
 * immediately after registration (backend sets status="true"); admins can
 * still disable/re-enable accounts from the Users page.
 */
export default function SignupPage(): JSX.Element {
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', contactNumber: '', email: '', password: '', confirm: '' });
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [loading, setLoading] = useState(false);

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!form.name.trim() || !form.contactNumber.trim() || !form.email.trim() || !form.password) {
      setError('All fields are required.');
      return;
    }
    if (form.password.length < 6) {
      setError('Password must be at least 6 characters.');
      return;
    }
    if (form.password !== form.confirm) {
      setError('Passwords do not match.');
      return;
    }
    setLoading(true);
    try {
      await api.signup({
        name: form.name.trim(),
        contactNumber: form.contactNumber.trim(),
        email: form.email.trim(),
        password: form.password,
      });
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Registration failed.');
    } finally {
      setLoading(false);
    }
  };

  if (done) {
    return (
      <div className="auth-wrap">
        <div className="card auth-card" style={{ textAlign: 'center' }}>
          <div className="auth-logo" aria-hidden="true">✅</div>
          <h1 className="auth-title">Registration completed</h1>
          <p className="auth-sub">
            Your account was created and is active. You can sign in immediately with the
            email and password you just registered.
          </p>
          <Button variant="primary" onClick={() => navigate('/login')} style={{ width: '100%' }}>
            Back to sign in
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-wrap">
      <div className="card auth-card">
        <div className="auth-logo" aria-hidden="true">☕</div>
        <h1 className="auth-title">Create an account</h1>
        <p className="auth-sub">Create an account to sign in right away</p>
        <form onSubmit={onSubmit} noValidate>
          <div className="field">
            <label htmlFor="su-name">Full name</label>
            <input id="su-name" className="input" value={form.name} onChange={set('name')} required />
          </div>
          <div className="field">
            <label htmlFor="su-contact">Contact number</label>
            <input id="su-contact" className="input" inputMode="tel" value={form.contactNumber} onChange={set('contactNumber')} required />
          </div>
          <div className="field">
            <label htmlFor="su-email">Email</label>
            <input id="su-email" className="input" type="email" autoComplete="email" value={form.email} onChange={set('email')} required />
          </div>
          <div className="form-row">
            <div className="field">
              <label htmlFor="su-pass">Password</label>
              <input id="su-pass" className="input" type="password" value={form.password} onChange={set('password')} required />
            </div>
            <div className="field">
              <label htmlFor="su-confirm">Confirm password</label>
              <input id="su-confirm" className="input" type="password" value={form.confirm} onChange={set('confirm')} required />
            </div>
          </div>
          {error && (
            <p className="field-error" role="alert" style={{ marginBottom: 12 }}>
              {error}
            </p>
          )}
          <Button variant="primary" type="submit" loading={loading} style={{ width: '100%' }}>
            Create account
          </Button>
        </form>
        <div className="auth-alt">
          Already registered? <Link to="/login">Sign in</Link>
        </div>
      </div>
    </div>
  );
}
