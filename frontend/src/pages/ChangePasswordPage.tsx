import { useState, type FormEvent } from 'react';
import * as api from '../api/cafe';
import { Button, Card, ToastStack, useToasts } from '../components/ui';

export default function ChangePasswordPage(): JSX.Element {
  const { toasts, push, dismiss } = useToasts();
  const [form, setForm] = useState({ oldPassword: '', newPassword: '', confirm: '' });
  const [saving, setSaving] = useState(false);
  const [ok, setOk] = useState(false);

  const onSubmit = async (e: FormEvent): Promise<void> => {
    e.preventDefault();
    if (form.newPassword.length < 6) {
      push('err', 'New password must be at least 6 characters (backend requirement).');
      return;
    }
    if (form.newPassword !== form.confirm) {
      push('err', 'New passwords do not match.');
      return;
    }
    setSaving(true);
    try {
      const message = await api.changePassword(form.oldPassword, form.newPassword);
      push('ok', message);
      setOk(true);
      setForm({ oldPassword: '', newPassword: '', confirm: '' });
    } catch (err) {
      // "Incorrect Old Password" (400) and other backend messages surface verbatim
      push('err', err instanceof Error ? err.message : 'Password change failed.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title="Change password" className="card" style={{ maxWidth: 460 }}>
      {ok && (
        <p style={{ color: 'var(--success)', fontWeight: 650 }} role="status">
          Password updated — use the new password at next sign-in.
        </p>
      )}
      <form onSubmit={onSubmit} noValidate>
        <div className="field">
          <label htmlFor="cp-old">Current password</label>
          <input id="cp-old" className="input" type="password" autoComplete="current-password" value={form.oldPassword} onChange={(e) => setForm({ ...form, oldPassword: e.target.value })} required />
        </div>
        <div className="form-row">
          <div className="field">
            <label htmlFor="cp-new">New password</label>
            <input id="cp-new" className="input" type="password" autoComplete="new-password" value={form.newPassword} onChange={(e) => setForm({ ...form, newPassword: e.target.value })} required minLength={6} />
          </div>
          <div className="field">
            <label htmlFor="cp-confirm">Confirm new password</label>
            <input id="cp-confirm" className="input" type="password" autoComplete="new-password" value={form.confirm} onChange={(e) => setForm({ ...form, confirm: e.target.value })} required />
          </div>
        </div>
        <Button variant="primary" type="submit" loading={saving}>
          Update password
        </Button>
      </form>
      <ToastStack toasts={toasts} dismiss={dismiss} />
    </Card>
  );
}
