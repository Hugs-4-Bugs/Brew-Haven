/**
 * Automation (admin) — DETECT → ANALYZE → DECIDE → ACT → MEASURE.
 *
 * Detection rules (win-back, VIP) run over real segment data and create DRAFT
 * campaigns only; sending always requires explicit action on the Campaigns
 * page plus a configured channel. Every run is audited in automation_runs.
 */

import { useCallback, useEffect, useState } from 'react';
import { Button, Card, ErrorState, LoadingState, useToasts, ToastStack } from '../components/ui';
import { getAutomationRuns, getAutomationStatus, runAutomationRule, type AutomationRunRow } from '../api/revenue';

export default function AutomationPage(): JSX.Element {
  const { toasts, push, dismiss } = useToasts();
  const [runs, setRuns] = useState<AutomationRunRow[] | null>(null);
  const [status, setStatus] = useState<Awaited<ReturnType<typeof getAutomationStatus>> | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    Promise.all([getAutomationRuns(), getAutomationStatus()]).then(([r, s]) => {
      setRuns(r.runs);
      setStatus(s);
    }).catch((err) => setError(err instanceof Error ? err.message : 'Failed to load automation.'));
  }, []);

  useEffect(load, [load]);

  const runRule = async (rule: string) => {
    setBusy(rule);
    try {
      const msg = await runAutomationRule(rule);
      push('ok', msg);
      load();
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Rule failed.');
    } finally {
      setBusy(null);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!runs || !status) return <LoadingState label="Loading automation status…" />;

  return (
    <>
      <Card>
        <p className="muted" style={{ margin: 0 }}>
          {status.note} Channel status:{' '}
          {status.channels.map((ch) => (
            <span key={ch.id} className={`badge ${ch.configured && ch.id !== 'NONE' ? 'badge-ok' : 'badge-off'}`} style={{ marginRight: 6 }}>
              {ch.id} {ch.configured ? (ch.id === 'NONE' ? '(record-only)' : '(configured)') : '(not configured)'}
            </span>
          ))}
          {' '}Nightly detection sweep: <strong>{status.scheduledEnabled ? 'enabled' : 'disabled'}</strong> (cafe.automation.scheduled-enabled).
        </p>
      </Card>

      <Card title="Run a detection rule">
        <div className="automation-actions">
          <Button variant="primary" loading={busy === 'WIN_BACK_DETECTION'} onClick={() => void runRule('WIN_BACK_DETECTION')}>
            🕐 Detect inactive customers (win-back draft)
          </Button>
          <Button variant="primary" loading={busy === 'VIP_RECOGNITION'} onClick={() => void runRule('VIP_RECOGNITION')}>
            ⭐ Detect high-value customers (VIP draft)
          </Button>
        </div>
        <p className="muted" style={{ marginBottom: 0 }}>
          Rules compute segments from real bills and create <strong>DRAFT</strong> campaigns on the Campaigns page —
          review, activate and send there. Nothing is ever sent automatically.
        </p>
      </Card>

      <Card title="Audit trail (automation_runs)">
        {runs.length === 0 ? (
          <p className="muted">No automation runs yet. Order thank-yous are recorded here automatically after each customer order.</p>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr><th>When</th><th>Rule</th><th>Trigger</th><th>Status</th><th>Detected</th><th>Actions</th><th>Channel</th></tr>
              </thead>
              <tbody>
                {runs.map((r) => (
                  <tr key={r.id}>
                    <td>{new Date(r.createdAt).toLocaleString()}</td>
                    <td><strong>{r.rule}</strong></td>
                    <td>{r.trigger}</td>
                    <td>
                      <span className={`badge ${r.status === 'SUCCESS' ? 'badge-ok' : r.status === 'SKIPPED' ? 'badge-off' : 'badge-err'}`}>{r.status}</span>
                    </td>
                    <td>{r.detectedCount}</td>
                    <td>{r.actionCount}</td>
                    <td>{r.channel ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
