/**
 * Campaigns (admin) — create, activate, send and measure campaigns.
 * Delivery goes through the NotificationChannel adapter; when no provider is
 * configured, sends are recorded honestly (channel NONE) and the UI says so.
 */

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button, Card, ErrorState, LoadingState, useEntrance, useToasts, ToastStack } from '../components/ui';
import {
  createCampaign, getAdminCampaigns, sendCampaign, setCampaignStatus,
  type AdminCampaign,
} from '../api/revenue';
import { formatMoney } from '../utils/format';

const SEGMENTS = ['ALL', 'NEW', 'RETURNING', 'FREQUENT', 'HIGH_VALUE', 'INACTIVE'];

export default function CampaignsPage(): JSX.Element {
  const { toasts, push, dismiss } = useToasts();
  const [campaigns, setCampaigns] = useState<AdminCampaign[] | null>(null);
  const [channel, setChannel] = useState<{ id: string; configured: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: '', segment: 'ALL', offerType: 'PERCENT', offerValue: 10, startsAt: '', endsAt: '', usageLimit: '' });

  const load = useCallback(() => {
    setError(null);
    getAdminCampaigns().then((d) => {
      setCampaigns(d.campaigns);
      setChannel({ id: d.channel, configured: d.channelConfigured });
    }).catch((err) => setError(err instanceof Error ? err.message : 'Failed to load campaigns.'));
  }, []);

  useEffect(load, [load]);
  useEntrance('.campaign-card');

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.name.trim()) {
      push('err', 'Campaign name is required.');
      return;
    }
    setCreating(true);
    try {
      const msg = await createCampaign({
        name: form.name.trim(),
        segment: form.segment,
        offerType: form.offerType,
        offerValue: form.offerType === 'NONE' ? undefined : Number(form.offerValue) || undefined,
        startsAt: form.startsAt || undefined,
        endsAt: form.endsAt || undefined,
        usageLimit: form.usageLimit ? Number(form.usageLimit) : undefined,
      });
      push('ok', msg);
      setForm({ ...form, name: '' });
      load();
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Create failed.');
    } finally {
      setCreating(false);
    }
  };

  const act = async (fn: () => Promise<string | { sent: number; failed: number; channel: string; note?: string }>) => {
    try {
      const result = await fn();
      if (typeof result === 'string') push('ok', result);
      else push('ok', `Sent to ${result.sent} customer(s) via ${result.channel}${result.note ? ' — ' + result.note : ''}`);
      load();
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Action failed.');
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!campaigns) return <LoadingState label="Loading campaigns…" />;

  return (
    <>
      <Card>
        <p className="muted" style={{ margin: 0 }}>
          Campaigns target segments computed from real orders. Delivery channel:{' '}
          {channel?.configured
            ? <strong className="badge badge-ok">EMAIL configured</strong>
            : <strong className="badge badge-off">No provider configured</strong>}
          {channel && !channel.configured && (
            <> — sends are <strong>recorded as events only</strong> (nothing is faked). Configure
            <code> spring.mail.*</code> or add a WhatsApp/SMS channel for real delivery.</>
          )}
        </p>
      </Card>

      <Card title="Create campaign (starts as DRAFT)">
        <form onSubmit={submit} noValidate className="campaign-form">
          <div className="field">
            <label htmlFor="cp-name">Name</label>
            <input id="cp-name" className="input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
          </div>
          <div className="form-row">
            <div className="field">
              <label htmlFor="cp-segment">Target segment</label>
              <select id="cp-segment" className="input" value={form.segment} onChange={(e) => setForm({ ...form, segment: e.target.value })}>
                {SEGMENTS.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="cp-offer">Offer</label>
              <select id="cp-offer" className="input" value={form.offerType} onChange={(e) => setForm({ ...form, offerType: e.target.value })}>
                <option value="PERCENT">% off</option>
                <option value="FLAT">₹ off</option>
                <option value="NONE">No discount (announcement)</option>
              </select>
            </div>
            {form.offerType !== 'NONE' && (
              <div className="field">
                <label htmlFor="cp-value">Value</label>
                <input id="cp-value" className="input" type="number" min={1} value={form.offerValue}
                  onChange={(e) => setForm({ ...form, offerValue: Number(e.target.value) })} />
              </div>
            )}
          </div>
          <div className="form-row">
            <div className="field">
              <label htmlFor="cp-start">Starts (optional)</label>
              <input id="cp-start" className="input" type="date" value={form.startsAt} onChange={(e) => setForm({ ...form, startsAt: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="cp-end">Ends (optional)</label>
              <input id="cp-end" className="input" type="date" value={form.endsAt} onChange={(e) => setForm({ ...form, endsAt: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="cp-limit">Conversion limit</label>
              <input id="cp-limit" className="input" type="number" min={1} placeholder="unlimited" value={form.usageLimit}
                onChange={(e) => setForm({ ...form, usageLimit: e.target.value })} />
            </div>
          </div>
          <Button variant="primary" type="submit" loading={creating}>Create draft campaign</Button>
        </form>
      </Card>

      {campaigns.length === 0 ? (
        <Card><p className="muted">No campaigns yet — create your first one above.</p></Card>
      ) : (
        <div className="campaign-list">
          {campaigns.map((c) => (
            <Card key={c.id} className="campaign-card">
              <div className="campaign-row">
                <div>
                  <strong>{c.name}</strong>{' '}
                  <span className={`badge ${c.status === 'ACTIVE' ? 'badge-ok' : 'badge-off'}`}>{c.status}</span>
                  <div className="muted" style={{ fontSize: 13 }}>
                    Code <span className="mono">{c.code}</span> · segment {c.segment}
                    {c.offerValue != null && c.offerType !== 'NONE' && <> · {c.offerType === 'PERCENT' ? `${c.offerValue}% off` : `${formatMoney(c.offerValue)} off`}</>}
                    {c.usageLimit != null && <> · limit {c.usageLimit}</>}
                  </div>
                </div>
                <div className="campaign-actions">
                  {c.status === 'DRAFT' && (
                    <Button size="sm" variant="primary" onClick={() => void act(() => setCampaignStatus(c.id, 'ACTIVE'))}>Activate</Button>
                  )}
                  {c.status === 'ACTIVE' && (
                    <Button size="sm" variant="primary" onClick={() => void act(() => sendCampaign(c.id))}>Send to segment</Button>
                  )}
                  {c.status !== 'ARCHIVED' && (
                    <Button size="sm" variant="ghost" onClick={() => void act(() => setCampaignStatus(c.id, 'ARCHIVED'))}>Archive</Button>
                  )}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
