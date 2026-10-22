/**
 * Revenue Intelligence (admin) — sales, customers, products, campaigns,
 * upsells, cart funnel and rule-based AI insights. Every number comes from
 * the real /analytics/* endpoints; nothing is invented client-side.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  Button, Card, ErrorState, LoadingState, StatCard, useEntrance,
} from '../components/ui';
import {
  getAnalyticsOverview, getAnalyticsInsights, getAnalyticsUpsells, getCartFunnel,
  getCampaignAnalytics, getFeedbackAll,
  type AnalyticsOverview, type CampaignPerformance, type Insight,
} from '../api/revenue';
import { formatMoney } from '../utils/format';

export default function AnalyticsPage(): JSX.Element {
  const [overview, setOverview] = useState<AnalyticsOverview | null>(null);
  const [insights, setInsights] = useState<Insight[] | null>(null);
  const [upsells, setUpsells] = useState<Awaited<ReturnType<typeof getAnalyticsUpsells>> | null>(null);
  const [funnel, setFunnel] = useState<Awaited<ReturnType<typeof getCartFunnel>> | null>(null);
  const [campaigns, setCampaigns] = useState<CampaignPerformance[] | null>(null);
  const [feedback, setFeedback] = useState<Awaited<ReturnType<typeof getFeedbackAll>> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    Promise.all([
      getAnalyticsOverview(), getAnalyticsInsights(), getAnalyticsUpsells(), getCartFunnel(), getCampaignAnalytics(), getFeedbackAll(),
    ]).then(([o, i, u, f, c, fb]) => {
      setOverview(o); setInsights(i.insights); setUpsells(u); setFunnel(f); setCampaigns(c.campaigns); setFeedback(fb);
    }).catch((err) => setError(err instanceof Error ? err.message : 'Failed to load analytics.'));
  }, []);

  useEffect(load, [load]);
  useEntrance('.analytics-grid > *');

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!overview || !insights || !upsells || !funnel || !campaigns || !feedback) {
    return <LoadingState label="Computing revenue intelligence from real data…" />;
  }

  const { sales, customers, products } = overview;

  return (
    <>
      <Card>
        <p className="muted" style={{ margin: 0 }}>
          Every figure below is computed live from real bills, payments, campaigns and recorded guest
          events. Insights are produced by the built-in <strong>rule-based engine</strong> (no external AI
          service is called) and each one carries its sample size — below the data threshold it honestly
          reports <em>“Not enough data yet.”</em>
        </p>
      </Card>

      <div className="analytics-grid">
        <StatCard label="Total revenue" value={formatMoney(sales.totalRevenue)} ico="💰" />
        <StatCard label="Orders" value={sales.orderCount} ico="🧾" />
        <StatCard label="Avg order value" value={formatMoney(sales.avgOrderValue)} ico="📈" />
        <StatCard label="Revenue — last 7 days" value={formatMoney(sales.revenueLast7Days)} ico="🗓️" />
        <StatCard label="Customers" value={customers.customerCount} ico="👥" />
        <StatCard label="Repeat rate" value={`${customers.repeatRate}%`} ico="🔁" />
        <StatCard label="High-value customers" value={customers.highValueCustomers} ico="⭐" />
        <StatCard label="Avg feedback rating" value={feedback.count ? `${feedback.averageRating} / 5` : '—'} ico="🌟" />
      </div>

      <Card title="AI insights (rule-based, data-grounded)">
        <ul className="insight-list">
          {insights.map((ins) => (
            <li key={ins.type} className={`insight ${ins.enoughData ? '' : 'insight-nodata'}`}>
              <div className="insight-head">
                <strong>{ins.title}</strong>
                <span className={`badge ${ins.enoughData ? 'badge-ok' : 'badge-off'}`}>
                  {ins.enoughData ? `${ins.confidence} · n=${ins.sampleSize}` : 'no data'}
                </span>
              </div>
              <p className="insight-body">{ins.body}</p>
            </li>
          ))}
        </ul>
      </Card>

      <div className="analytics-cols">
        <Card title="Best sellers (by quantity)">
          {products.bestSellersByQuantity.length === 0
            ? <p className="muted">No orders yet.</p>
            : (
              <table className="data">
                <thead><tr><th>Product</th><th>Units</th></tr></thead>
                <tbody>
                  {products.bestSellersByQuantity.map((p) => (
                    <tr key={p.name}><td>{p.name}</td><td><strong>{p.value}</strong></td></tr>
                  ))}
                </tbody>
              </table>
            )}
        </Card>

        <Card title="Slow movers (active, zero orders)">
          {products.slowMovers.length === 0
            ? <p className="muted">Every active product has sold at least once. 🎉</p>
            : <ul className="muted">{products.slowMovers.map((n) => <li key={n}>{n}</li>)}</ul>}
        </Card>

        <Card title="Category performance (revenue)">
          {products.categoryPerformance.length === 0
            ? <p className="muted">No orders yet.</p>
            : (
              <table className="data">
                <thead><tr><th>Category</th><th>Revenue</th></tr></thead>
                <tbody>
                  {products.categoryPerformance.map((c) => (
                    <tr key={c.name}><td>{c.name}</td><td>{formatMoney(c.value)}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
        </Card>

        <Card title="Upsell effectiveness (client-reported)">
          {upsells.totalShown === 0
            ? <p className="muted">No pairing suggestions recorded from signed-in sessions yet.</p>
            : (
              <>
                <p className="muted">
                  {upsells.totalShown} shown · {upsells.totalAccepted} accepted · {upsells.acceptRate}% accept rate
                </p>
                <table className="data">
                  <thead><tr><th>Product</th><th>Shown</th><th>Accepted</th><th>Rate</th></tr></thead>
                  <tbody>
                    {upsells.upsells.slice(0, 5).map((u) => (
                      <tr key={u.productId}><td>{u.productName}</td><td>{u.shown}</td><td>{u.accepted}</td><td>{u.acceptRate}%</td></tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
        </Card>

        <Card title="Cart funnel (client-reported)">
          <p className="muted">
            {funnel.cartCreated} carts created → {funnel.checkoutStarted} checkouts started → {funnel.orderCompleted} orders completed.
            Abandonment rate {funnel.abandonmentRate}%. Recovery is available through a win-back campaign
            (Automation page) — nothing is ever sent without a configured channel and explicit admin action.
          </p>
        </Card>

        <Card title="Campaign performance & revenue attribution">
          {campaigns.length === 0
            ? <p className="muted">No campaigns yet — create one on the Campaigns page.</p>
            : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr><th>Campaign</th><th>Status</th><th>Sent</th><th>Clicked</th><th>Converted</th><th>Revenue</th><th>Rate</th></tr>
                  </thead>
                  <tbody>
                    {campaigns.map((c) => (
                      <tr key={c.id}>
                        <td><strong>{c.name}</strong><br /><span className="mono muted">{c.code}</span></td>
                        <td>{c.status}</td>
                        <td>{c.sent}</td>
                        <td>{c.clicked}</td>
                        <td>{c.converted}</td>
                        <td><strong>{formatMoney(c.revenue)}</strong></td>
                        <td>{c.conversionRate}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          <p className="muted" style={{ marginBottom: 0 }}>
            Revenue is attributed ONLY from server-verified conversions (campaign code applied at checkout).
          </p>
        </Card>
      </div>

      <Card title="Latest feedback">
        {feedback.count === 0
          ? <p className="muted">No customer feedback yet.</p>
          : (
            <ul className="feedback-admin-list">
              {feedback.feedback.slice(0, 8).map((f) => (
                <li key={f.billUuid}>
                  <span aria-hidden="true">{'⭐'.repeat(f.rating)}</span>
                  <span className="mono muted">{f.email}</span>
                  {f.comment && <span className="muted">“{f.comment}”</span>}
                </li>
              ))}
            </ul>
          )}
      </Card>

      <div style={{ marginTop: 16 }}>
        <Button variant="ghost" size="sm" onClick={load}>↻ Recompute</Button>
      </div>
    </>
  );
}
