/**
 * Customers (admin) — CRM segmentation computed from real bills only.
 * No customer rows are invented: people appear here once they have orders.
 */

import { useCallback, useEffect, useState } from 'react';
import { Card, ErrorState, LoadingState, useEntrance } from '../components/ui';
import { getAnalyticsCustomers, type CustomerRow } from '../api/revenue';
import { formatMoney } from '../utils/format';

const SEGMENT_LABELS: Record<string, string> = {
  NEW: 'New',
  RETURNING: 'Returning',
  FREQUENT: 'Frequent',
  HIGH_VALUE: 'High value',
  INACTIVE: 'Inactive',
  RECENTLY_ACTIVE: 'Recently active',
};

export default function CustomersPage(): JSX.Element {
  const [rows, setRows] = useState<CustomerRow[] | null>(null);
  const [counts, setCounts] = useState<Record<string, number> | null>(null);
  const [threshold, setThreshold] = useState(1000);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    getAnalyticsCustomers().then((d) => {
      setRows(d.customers);
      setCounts(d.segmentCounts);
      setThreshold(d.highValueThreshold);
    }).catch((err) => setError(err instanceof Error ? err.message : 'Failed to load customers.'));
  }, []);

  useEffect(load, [load]);
  useEntrance('.customers-table tbody tr');

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!rows || !counts) return <LoadingState label="Segmenting customers from real orders…" />;

  return (
    <>
      <Card>
        <p className="muted" style={{ margin: 0 }}>
          Segments are derived exclusively from real bills: <strong>New</strong> (1 order),{' '}
          <strong>Returning</strong> (2-3), <strong>Frequent</strong> (4+), <strong>High value</strong>{' '}
          (₹{threshold}+ across 2+ orders), <strong>Inactive</strong> (last order &gt; 30 days ago).
          Customers with no orders are not listed — nothing is fabricated.
        </p>
      </Card>

      <div className="seg-cards">
        {Object.entries(SEGMENT_LABELS).map(([key, label]) => (
          <div key={key} className={`seg-chip seg-${key.toLowerCase().replace('_', '-')}`}>
            <span className="seg-count">{counts[key] ?? 0}</span>
            <span className="seg-label">{label}</span>
          </div>
        ))}
      </div>

      {rows.length === 0 ? (
        <Card><p className="muted">No customers with orders yet — segments appear as soon as real bills exist.</p></Card>
      ) : (
        <div className="table-wrap customers-table">
          <table className="data">
            <thead>
              <tr><th>Customer</th><th>Orders</th><th>Total spent</th><th>Avg order</th><th>Last order</th><th>Segments</th></tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.email}>
                  <td>
                    <strong>{c.name || '—'}</strong>
                    <br />
                    <span className="mono muted">{c.email}</span>
                  </td>
                  <td>{c.orders}</td>
                  <td><strong>{formatMoney(c.totalSpent)}</strong></td>
                  <td>{formatMoney(c.avgOrderValue)}</td>
                  <td>{c.lastOrderAt ? new Date(c.lastOrderAt).toLocaleDateString() : '—'}</td>
                  <td>
                    {c.segments.map((s) => (
                      <span key={s} className={`badge seg-badge seg-badge-${s.toLowerCase().replace('_', '-')}`}>
                        {SEGMENT_LABELS[s] ?? s}
                      </span>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
