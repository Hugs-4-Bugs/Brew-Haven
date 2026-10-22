/**
 * Checkout drawer — turns the guest cart into a REAL order through the
 * EXISTING billing + payment APIs (no new order tables, no new payments).
 *
 * Journey: review cart → (sign in if anonymous) → contact details (pre-filled
 * from /user/me) → payment method → campaign code (optional, server-verified)
 * → place order (POST /bill/generateReport) → thank-you + feedback.
 *
 * Payment options mirror the existing POS contract:
 *  - Cash at counter (bill only — payment happens at the counter, same as POS cash)
 *  - UPI (VPA verify + pay via the existing /api/payment/upi endpoints)
 * Card and wallet payments remain counter-assisted (documented limitation).
 */

import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { getMyProfile, placeOrder, recordEvent, triggerThankYou } from '../../api/revenue';
import type { ActiveCampaign } from '../../api/revenue';
import { verifyUpiId, payWithUpi } from '../../api/cafe';
import { cartTotal, clearCart, removeFromCart, setQuantity, toProductDetails, type CartLine } from '../../guest/cartStore';
import { formatMoney } from '../../utils/format';
import FeedbackForm from './FeedbackForm';

interface CheckoutDrawerProps {
  open: boolean;
  onClose: () => void;
  lines: CartLine[];
  appliedCampaign: ActiveCampaign | null;
  onClearCampaign: () => void;
}

type Stage = 'form' | 'paying' | 'done';

export default function CheckoutDrawer({ open, onClose, lines, appliedCampaign, onClearCampaign }: CheckoutDrawerProps): JSX.Element | null {
  const { token } = useAuth();
  const navigate = useNavigate();
  const [stage, setStage] = useState<Stage>('form');
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [email, setEmail] = useState('');
  const [paymentMethod, setPaymentMethod] = useState<'Cash' | 'UPI'>('Cash');
  const [upiId, setUpiId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [orderUuid, setOrderUuid] = useState<string | null>(null);
  const [campaignCode, setCampaignCode] = useState('');

  const total = useMemo(() => cartTotal(lines), [lines]);

  // Pre-fill from the signed-in account's own profile.
  useEffect(() => {
    if (!open || !token) return;
    let cancelled = false;
    getMyProfile().then((p) => {
      if (cancelled) return;
      setName(p.name);
      setContact(p.contactNumber);
      setEmail(p.email);
    }).catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [open, token]);

  useEffect(() => {
    if (open) setCampaignCode(appliedCampaign?.code ?? '');
  }, [open, appliedCampaign]);

  if (!open) return null;

  const requiresLogin = !token;

  const close = () => {
    if (stage === 'done') clearCart();
    if (stage === 'done') void recordEvent('ORDER_COMPLETED', undefined, orderUuid ?? undefined);
    setStage('form');
    setError(null);
    setOrderUuid(null);
    onClose();
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (requiresLogin) {
      navigate('/login', { state: { from: '/menu' } });
      return;
    }
    if (!name.trim() || !contact.trim() || !email.trim()) {
      setError('Please fill in your name, contact number and email.');
      return;
    }
    setBusy(true);
    try {
      void recordEvent('CHECKOUT_STARTED');

      // UPI: verify + pay through the EXISTING endpoints (mirrors the POS flow).
      if (paymentMethod === 'UPI') {
        const vpa = upiId.trim();
        if (!vpa) {
          setError('Enter your UPI ID (e.g. name@bank).');
          setBusy(false);
          return;
        }
        const check = await verifyUpiId(vpa);
        if (!check.valid) {
          setError(check.message || 'Invalid UPI ID.');
          setBusy(false);
          return;
        }
        await payWithUpi({ userId: email.trim(), upiId: vpa, amount: total });
      }

      setStage('paying');
      const placed = await placeOrder({
        name: name.trim(),
        contactNumber: contact.trim(),
        email: email.trim(),
        paymentMethod: paymentMethod === 'UPI' ? 'UPI' : 'Cash',
        productDetails: toProductDetails(lines),
        totalAmount: total,
        isGenerate: true,
        campaignCode: campaignCode.trim() || undefined,
      });
      setOrderUuid(placed.uuid);
      void triggerThankYou(placed.uuid);
      setStage('done');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Order failed. Please try again.');
      setStage('form');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="drawer-overlay" role="dialog" aria-modal="true" aria-label="Checkout" onClick={close}>
      <div className="drawer" onClick={(e) => e.stopPropagation()}>
        {stage === 'done' ? (
          <div className="checkout-done">
            <div className="checkout-done-emoji" aria-hidden="true">🎉</div>
            <h2>Thank you — order placed!</h2>
            <p className="checkout-done-sub">
              Your bill <code>{orderUuid}</code> for <strong>{formatMoney(total)}</strong> was created
              {campaignCode.trim() && <> with offer code <code>{campaignCode.trim()}</code></>}. Pay at the counter
              {paymentMethod === 'UPI' ? ' (UPI payment recorded)' : ''} and show this bill id.
            </p>
            <FeedbackForm billUuid={orderUuid ?? ''} />
            <button type="button" className="btn btn-primary" style={{ width: '100%' }} onClick={close}>
              Back to menu
            </button>
          </div>
        ) : (
          <form onSubmit={submit} noValidate>
            <div className="drawer-head">
              <h2>Your order</h2>
              <button type="button" className="drawer-close" aria-label="Close checkout" onClick={close}>✕</button>
            </div>

            <ul className="cart-lines" aria-label="Cart items">
              {lines.map((l) => (
                <li key={l.id} className="cart-line">
                  <div className="cart-line-info">
                    <strong>{l.name}</strong>
                    <span>{formatMoney(l.price)} each</span>
                  </div>
                  <div className="cart-line-qty">
                    <button type="button" aria-label={`Decrease ${l.name}`} onClick={() => setQuantity(l.id, l.quantity - 1)}>−</button>
                    <span aria-live="polite">{l.quantity}</span>
                    <button type="button" aria-label={`Increase ${l.name}`} onClick={() => setQuantity(l.id, l.quantity + 1)}>+</button>
                  </div>
                  <span className="cart-line-total">{formatMoney(l.price * l.quantity)}</span>
                  <button type="button" className="cart-line-remove" aria-label={`Remove ${l.name}`} onClick={() => removeFromCart(l.id)}>✕</button>
                </li>
              ))}
            </ul>

            <div className="cart-summary">
              <span>Total</span>
              <strong>{formatMoney(total)}</strong>
            </div>

            <div className="field">
              <label htmlFor="co-name">Name</label>
              <input id="co-name" className="input" value={name} onChange={(e) => setName(e.target.value)} required />
            </div>
            <div className="form-row">
              <div className="field">
                <label htmlFor="co-contact">Contact number</label>
                <input id="co-contact" className="input" inputMode="tel" value={contact} onChange={(e) => setContact(e.target.value)} required />
              </div>
              <div className="field">
                <label htmlFor="co-email">Email</label>
                <input id="co-email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
              </div>
            </div>

            <fieldset className="field">
              <legend>Payment</legend>
              <label className="menu-toggle">
                <input type="radio" name="pay" checked={paymentMethod === 'Cash'} onChange={() => setPaymentMethod('Cash')} />
                <span>Cash at counter</span>
              </label>
              <label className="menu-toggle">
                <input type="radio" name="pay" checked={paymentMethod === 'UPI'} onChange={() => setPaymentMethod('UPI')} />
                <span>UPI (pay now)</span>
              </label>
              {paymentMethod === 'UPI' && (
                <div className="field" style={{ marginTop: 8 }}>
                  <label htmlFor="co-upi">UPI ID</label>
                  <input id="co-upi" className="input" placeholder="name@bank" value={upiId} onChange={(e) => setUpiId(e.target.value)} />
                </div>
              )}
            </fieldset>

            <div className="field">
              <label htmlFor="co-campaign">Offer code (optional)</label>
              <input
                id="co-campaign"
                className="input"
                placeholder={appliedCampaign ? appliedCampaign.code : 'e.g. BREW1'}
                value={campaignCode}
                onChange={(e) => setCampaignCode(e.target.value)}
              />
              {appliedCampaign && (
                <span className="field-hint">
                  Applied from the banner — <button type="button" className="linklike" onClick={onClearCampaign}>clear</button>
                </span>
              )}
            </div>

            {error && <p className="field-error" role="alert">{error}</p>}

            {requiresLogin ? (
              <button type="submit" className="btn btn-primary" style={{ width: '100%' }}>
                Sign in to place the order →
              </button>
            ) : (
              <button type="submit" className="btn btn-primary" style={{ width: '100%' }} disabled={busy || lines.length === 0}>
                {busy ? 'Placing order…' : `Place order — ${formatMoney(total)}`}
              </button>
            )}
          </form>
        )}
      </div>
    </div>
  );
}
