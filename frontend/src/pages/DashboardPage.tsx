import { useCallback, useEffect, useMemo, useState } from 'react';
import * as api from '../api/cafe';
import { Card, ErrorState, LoadingState, StatCard, useEntrance } from '../components/ui';
import { parseBillLines } from '../utils/format';

interface DayRevenue {
  day: string;
  revenue: number;
  bills: number;
}

/** Revenue per day for the last 7 days from real bill data. */
function computeRevenueByDay(bills: api.Bill[]): DayRevenue[] {
  const days: DayRevenue[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - i);
    const next = new Date(d);
    next.setDate(next.getDate() + 1);
    const dayBills = bills.filter((b) => {
      // bills have no timestamp column in the backend contract — id ordering is the
      // stable proxy; uuid embeds epoch millis (BILL-<millis>) so we can date them.
      const m = /^BILL-(\d+)$/.exec(b.uuid || '');
      if (!m) return false;
      const t = Number(m[1]);
      return t >= d.getTime() && t < next.getTime();
    });
    days.push({
      day: d.toLocaleDateString('en-IN', { weekday: 'short' }),
      revenue: dayBills.reduce((sum, b) => sum + (b.total ?? 0), 0),
      bills: dayBills.length,
    });
  }
  return days;
}

function computeTopItems(bills: api.Bill[], limit = 5): { name: string; qty: number; revenue: number }[] {
  const agg = new Map<string, { qty: number; revenue: number }>();
  for (const bill of bills) {
    for (const line of parseBillLines(bill.productDetails)) {
      const entry = agg.get(line.name) ?? { qty: 0, revenue: 0 };
      entry.qty += line.quantity;
      entry.revenue += line.total;
      agg.set(line.name, entry);
    }
  }
  return [...agg.entries()]
    .map(([name, v]) => ({ name, ...v }))
    .sort((a, b) => b.qty - a.qty)
    .slice(0, limit);
}

/** Lightweight SVG bar chart (no chart library — keeps the bundle lean). */
function BarChart({ data }: { data: DayRevenue[] }): JSX.Element {
  const max = Math.max(1, ...data.map((d) => d.revenue));
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, height: 170, paddingTop: 8 }}>
      {data.map((d) => {
        const h = Math.round((d.revenue / max) * 140);
        return (
          <div key={d.day} style={{ flex: 1, textAlign: 'center' }} title={`${d.day}: ₹${d.revenue.toLocaleString()} (${d.bills} bills)`}>
            <div className="muted" style={{ fontSize: 11, marginBottom: 4 }}>₹{d.revenue.toLocaleString()}</div>
            <div
              style={{
                height: Math.max(h, 3),
                borderRadius: '6px 6px 2px 2px',
                background: d.revenue > 0
                  ? 'linear-gradient(180deg, var(--accent-strong), rgba(240,164,56,0.35))'
                  : 'var(--bg-2)',
                border: '1px solid var(--border)',
                transition: 'height 0.5s ease',
              }}
            />
            <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>{d.day}</div>
          </div>
        );
      })}
    </div>
  );
}

export default function DashboardPage(): JSX.Element {
  const [counts, setCounts] = useState<api.DashboardCounts | null>(null);
  const [bills, setBills] = useState<api.Bill[] | null>(null);
  const [products, setProducts] = useState<api.ProductWrapper[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [c, b, p] = await Promise.all([api.getDashboardCounts(), api.getBills(), api.getProducts()]);
      setCounts(c);
      setBills(b);
      setProducts(p);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load dashboard data.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEntrance('.stat-card, .card');

  const revenueByDay = useMemo(() => (bills ? computeRevenueByDay(bills) : []), [bills]);
  const topItems = useMemo(() => (bills ? computeTopItems(bills) : []), [bills]);
  const totalRevenue = useMemo(() => (bills ?? []).reduce((s, b) => s + (b.total ?? 0), 0), [bills]);
  const avgBill = bills && bills.length > 0 ? Math.round(totalRevenue / bills.length) : 0;
  const availableProducts = (products ?? []).filter((p) => p.status === 'true').length;

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!counts || !bills || !products) return <LoadingState label="Loading dashboard…" />;

  return (
    <>
      <div className="stat-grid">
        <StatCard label="Total revenue" value={totalRevenue} ico="💰" countTo={totalRevenue} />
        <StatCard label="Bills generated" value={counts.bill} ico="🧾" countTo={counts.bill} />
        <StatCard label="Products" value={`${availableProducts} / ${counts.product}`} ico="🍽️" />
        <StatCard label="Categories" value={counts.category} ico="🏷️" countTo={counts.category} />
      </div>

      <div className="grid-2">
        <Card title="Revenue — last 7 days">
          {bills.length === 0 ? (
            <p className="muted">No bills yet. Generate your first bill from the POS screen.</p>
          ) : (
            <BarChart data={revenueByDay} />
          )}
        </Card>
        <Card title="Best sellers">
          {topItems.length === 0 ? (
            <p className="muted">Item analytics appear once bills contain line items.</p>
          ) : (
            <div className="table-wrap" style={{ border: 'none' }}>
              <table className="data">
                <thead>
                  <tr>
                    <th>Item</th>
                    <th>Qty sold</th>
                    <th>Revenue</th>
                  </tr>
                </thead>
                <tbody>
                  {topItems.map((item) => (
                    <tr key={item.name}>
                      <td>{item.name}</td>
                      <td>{item.qty}</td>
                      <td>₹{item.revenue.toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="muted mt-2">Average bill value: ₹{avgBill.toLocaleString()}</p>
        </Card>
      </div>
    </>
  );
}
