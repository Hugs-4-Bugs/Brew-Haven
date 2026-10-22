/**
 * My Orders — the signed-in customer's own order history (GET /bill/myBills,
 * server-filtered to their email) with a one-click reorder into the cart.
 */

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import GuestNav from '../components/guest/GuestNav';
import { getMyBills } from '../api/revenue';
import type { Bill, BillLine } from '../api/cafe';
import { addToCart } from '../guest/cartStore';
import { resolveGuestMenu, type GuestMenuItem } from '../guest/menuSnapshot';
import { formatMoney } from '../utils/format';
import { loadGuestPrefRecord, PREFS_EVENT } from '../guest/preferenceStore';
import type { GuestPrefRecord } from '../guest/types';

interface MyOrdersPageProps { /* router */ }

export default function MyOrdersPage(_props: MyOrdersPageProps): JSX.Element {
  const [bills, setBills] = useState<Bill[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [menu, setMenu] = useState<GuestMenuItem[]>([]);
  const [reordered, setReordered] = useState<string | null>(null);
  const [record, setRecord] = useState<GuestPrefRecord | null>(null);

  useEffect(() => {
    setRecord(loadGuestPrefRecord());
    let cancelled = false;
    getMyBills().then((b) => {
      if (!cancelled) setBills(b);
    }).catch((err) => {
      if (!cancelled) setError(err instanceof Error ? err.message : 'Could not load orders.');
    });
    resolveGuestMenu().then((m) => {
      if (!cancelled) setMenu(m.items);
    });
    const onChange = () => setRecord(loadGuestPrefRecord());
    window.addEventListener(PREFS_EVENT, onChange);
    return () => {
      cancelled = true;
      window.removeEventListener(PREFS_EVENT, onChange);
    };
  }, []);

  const reorder = (bill: Bill) => {
    try {
      const lines = JSON.parse(bill.productDetails) as BillLine[];
      const byId = new Map(menu.map((i) => [i.id, i]));
      let added = 0;
      for (const line of lines) {
        const item = byId.get(line.id ?? -1) ?? byId.get(Number(line.id));
        if (item && item.status === 'true') {
          addToCart(item, line.quantity);
          added += 1;
        }
      }
      setReordered(added > 0 ? `${added} item${added === 1 ? '' : 's'} added to your cart.` : 'Those items are currently unavailable.');
    } catch {
      setReordered('Could not read that order\u2019s items.');
    }
  };

  return (
    <div className="guest-page">
      <GuestNav onEditPreferences={() => undefined} prefRecord={record} />
      <main className="menu-page-main">
        <header className="menu-header">
          <div>
            <span className="guest-eyebrow">Brew Haven</span>
            <h1 className="menu-title">My orders</h1>
            <p className="guest-section-sub">Your previous orders, straight from our billing records.</p>
          </div>
          <Link to="/menu" className="btn btn-outline">← Back to menu</Link>
        </header>

        {reordered && <p className="menu-taste-note" role="status">{reordered}</p>}
        {error && <p className="field-error" role="alert">{error}</p>}

        {!bills && !error && <p className="rec-empty">Loading your orders…</p>}
        {bills && bills.length === 0 && (
          <p className="rec-empty">
            No orders yet — <Link to="/menu">browse the menu</Link> and place your first one.
          </p>
        )}
        {bills && bills.length > 0 && (
          <div className="orders-list">
            {bills.map((b) => (
              <article key={b.id} className="order-card">
                <div className="order-head">
                  <div>
                    <strong>{formatMoney(b.total)}</strong>
                    <span className="order-meta">Bill {b.uuid} · {b.paymentMethod}</span>
                  </div>
                  <div className="order-actions">
                    <button type="button" className="btn btn-outline" onClick={() => reorder(b)}>Reorder</button>
                  </div>
                </div>
                <ul className="order-lines">
                  {(() => {
                    try {
                      return (JSON.parse(b.productDetails) as BillLine[]).map((l, i) => (
                        <li key={i}>{l.quantity} × {l.name}</li>
                      ));
                    } catch {
                      return <li>Item details unavailable</li>;
                    }
                  })()}
                </ul>
              </article>
            ))}
          </div>
        )}
      </main>
      <footer className="guest-footer">
        <div className="guest-footer-inner">
          <span className="guest-footer-brand">☕ Brew Haven</span>
          <p className="guest-footer-note">Order history shows bills created with your account email.</p>
        </div>
      </footer>
    </div>
  );
}
