/**
 * Checkout drawer — SERVER-PRICED checkout with real payment methods.
 *
 * Money integrity: every payable amount rendered here comes from the backend
 * pricing engine (POST /checkout/price for the live breakdown, POST
 * /checkout/intent at payment time — the backend re-prices server-side, so a
 * tampered browser cannot change what is charged). Cart prices in the line
 * list are display only.
 *
 * Idempotency: intents are created with a per-checkout-session idempotency key
 * derived from the cart + coupon + method. Double clicks, retries and browser
 * refreshes reuse the SAME server-side intent — one order, one payment.
 *
 * Methods:
 *  - UPI: provider checkout window (RAZORPAY mode) or merchant QR + live
 *    status polling + clearly-labelled TEST settlement panel (SANDBOX mode).
 *  - Card: provider-hosted checkout (card details are tokenized by the
 *    provider — raw card data never touches this app) or the TEST panel.
 *  - Cash at counter: bill generated at the server-computed amount; payment
 *    happens physically at pickup (no payment record is claimed).
 */

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { getMyProfile, recordEvent, triggerThankYou } from '../../api/revenue';
import type { ActiveCampaign } from '../../api/revenue';
import {
  priceCart,
  createPaymentIntent,
  getIntent,
  getIntentQr,
  confirmCheckout,
  sandboxSettle,
  cancelIntent,
  type PriceBreakdown,
  type CheckoutIntent,
  type CheckoutReceipt,
  type PayMethod,
} from '../../api/checkout';
import { cartCount, clearCart, removeFromCart, setQuantity, type CartLine } from '../../guest/cartStore';
import { formatMoney } from '../../utils/format';
import FeedbackForm from './FeedbackForm';

interface CheckoutDrawerProps {
  open: boolean;
  onClose: () => void;
  lines: CartLine[];
  appliedCampaign: ActiveCampaign | null;
  onClearCampaign: () => void;
}

type Stage = 'form' | 'pay' | 'done' | 'failed';

interface RazorpayHandlerResponse {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

interface RazorpayOptions {
  key: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  order_id: string;
  prefill?: { name?: string; email?: string; contact?: string };
  theme?: { color?: string };
  handler: (response: RazorpayHandlerResponse) => void;
  modal?: { ondismiss?: () => void };
}

interface RazorpayInstance {
  open: () => void;
}

interface WindowWithRazorpay extends Window {
  Razorpay?: new (options: RazorpayOptions) => RazorpayInstance;
}

function loadRazorpayScript(): Promise<boolean> {
  return new Promise((resolve) => {
    if (typeof window === 'undefined') return resolve(false);
    const w = window as WindowWithRazorpay;
    if (w.Razorpay) return resolve(true);
    const script = document.createElement('script');
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.onload = () => resolve(true);
    script.onerror = () => resolve(false);
    document.body.appendChild(script);
  });
}

function newSessionKey(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `s-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  }
}

/** Deterministic idempotency key: same cart + coupon + method + session = same intent. */
function clientKeyFor(session: string, lines: CartLine[], coupon: string, method: PayMethod): string {
  const cartSig = lines
    .slice()
    .sort((a, b) => a.id - b.id)
    .map((l) => `${l.id}x${l.quantity}`)
    .join(',');
  return `${session}:${method}:${coupon || '-'}:${cartSig}`;
}

export default function CheckoutDrawer({ open, onClose, lines, appliedCampaign, onClearCampaign }: CheckoutDrawerProps): JSX.Element | null {
  const { token } = useAuth();
  const navigate = useNavigate();
  const [stage, setStage] = useState<Stage>('form');
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [email, setEmail] = useState('');
  const [method, setMethod] = useState<PayMethod>('UPI');
  const [upiId, setUpiId] = useState('');
  const [couponInput, setCouponInput] = useState('');
  const [appliedCoupon, setAppliedCoupon] = useState<string | null>(null);
  const [quote, setQuote] = useState<PriceBreakdown | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [intent, setIntent] = useState<CheckoutIntent | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<CheckoutReceipt | null>(null);
  const sessionKey = useRef(newSessionKey());

  const requiresLogin = !token;
  const payable = quote?.total ?? 0;

  // ---------------------------------------------------------------- pricing (server-authoritative)

  const refreshQuote = useCallback(async (coupon?: string) => {
    if (lines.length === 0) {
      setQuote(null);
      setQuoteError(null);
      return;
    }
    setQuoteError(null);
    try {
      const q = await priceCart(
        lines.map((l) => ({ productId: l.id, quantity: l.quantity })),
        coupon || undefined,
        email || undefined,
      );
      setQuote(q);
      if (coupon) {
        if (q.couponApplied) {
          setAppliedCoupon(q.couponCode ?? coupon);
          setCouponError(null);
        } else {
          setAppliedCoupon(null);
          setCouponError(q.couponRejectionReason ?? 'This coupon could not be applied.');
        }
      } else {
        setAppliedCoupon(null);
        setCouponError(null);
      }
    } catch (err) {
      setQuoteError(err instanceof Error ? err.message : 'Could not price your cart.');
    }
  }, [lines, email]);

  // Re-price whenever the drawer opens or the cart changes (no coupon by default).
  useEffect(() => {
    if (open) void refreshQuote(appliedCoupon ?? undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, lines]);

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

  // Banner-applied campaign: prefill and auto-apply once per open.
  useEffect(() => {
    if (open && appliedCampaign?.code) {
      setCouponInput(appliedCampaign.code);
      void refreshQuote(appliedCampaign.code);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, appliedCampaign]);

  // ---------------------------------------------------------------- status polling (QR / waiting screen)

  useEffect(() => {
    if (stage !== 'pay' || !intent || !intent.checkout?.sandbox) return;
    const reference = intent.reference;
    let stopped = false;
    const poll = async (): Promise<void> => {
      try {
        const st = await getIntent(reference);
        if (stopped) return;
        if (st.status === 'SUCCESS') {
          setReceipt({
            reference: st.reference,
            status: 'SUCCESS',
            transactionId: st.providerPaymentId ?? st.reference,
            amount: st.total,
            method: st.method,
            billUuid: st.billUuid ?? null,
          });
          setStage('done');
        } else if (st.status === 'FAILED' || st.status === 'EXPIRED' || st.status === 'CANCELLED') {
          setError('This payment did not complete.');
          setStage('failed');
        }
      } catch {
        /* transient network error — keep polling */
      }
    };
    const timer = window.setInterval(poll, 2500);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }, [stage, intent]);

  if (!open) return null;

  // ---------------------------------------------------------------- helpers

  const close = () => {
    if (stage === 'pay' && intent) void cancelIntent(intent.reference).catch(() => undefined);
    if (stage === 'done') {
      clearCart();
      void recordEvent('ORDER_COMPLETED', undefined, receipt?.billUuid ?? undefined);
      // the cart (and therefore the coupon context) is gone — reset coupon state
      setCouponInput('');
      setAppliedCoupon(null);
      setCouponError(null);
    }
    setStage('form');
    setError(null);
    setIntent(null);
    setQr(null);
    setReceipt(null);
    sessionKey.current = newSessionKey();
    onClose();
  };

  const applyCoupon = async () => {
    const code = couponInput.trim();
    if (!code) {
      setCouponError('Enter an offer code first.');
      return;
    }
    setApplying(true);
    setCouponError(null);
    try {
      await refreshQuote(code);
    } finally {
      setApplying(false);
    }
  };

  const removeCoupon = () => {
    setCouponInput('');
    setCouponError(null);
    onClearCampaign();
    void refreshQuote();
  };

  const finishWithReceipt = (r: CheckoutReceipt, uuid: string | null | undefined) => {
    setReceipt({ ...r, billUuid: r.billUuid ?? uuid ?? null });
    setStage('done');
    if (uuid || r.billUuid) void triggerThankYou(String(r.billUuid ?? uuid));
  };

  const openRazorpay = async (created: CheckoutIntent) => {
    const ok = await loadRazorpayScript();
    const w = window as WindowWithRazorpay;
    if (!ok || !w.Razorpay || !created.checkout?.keyId) {
      setError('Could not open the payment window. Check your connection and try again.');
      setStage('form');
      return;
    }
    const rzp = new w.Razorpay({
      key: created.checkout.keyId,
      amount: created.checkout.amountPaise,
      currency: created.checkout.currency,
      name: 'Brew Haven',
      description: `Order ${created.reference}`,
      order_id: created.checkout.providerOrderId ?? '',
      prefill: { name, email, contact },
      theme: { color: '#b45309' },
      handler: (resp) => {
        confirmCheckout({
          reference: created.reference,
          providerOrderId: resp.razorpay_order_id,
          providerPaymentId: resp.razorpay_payment_id,
          signature: resp.razorpay_signature,
        })
          .then((r) => finishWithReceipt(r, undefined))
          .catch((err) => {
            setError(err instanceof Error ? err.message : 'Payment verification failed.');
            setStage('failed');
          });
      },
      modal: {
        ondismiss: () => {
          setStage('form');
          setError('Payment window closed before completion.');
        },
      },
    });
    rzp.open();
  };

  const startPayment = async () => {
    setError(null);
    if (requiresLogin) {
      navigate('/login', { state: { from: '/menu' } });
      return;
    }
    if (!name.trim() || !contact.trim() || !email.trim()) {
      setError('Please fill in your name, contact number and email.');
      return;
    }
    if (!quote || quote.total <= 0) {
      setError(quoteError ?? 'Your cart could not be priced. Please try again.');
      return;
    }
    setBusy(true);
    try {
      void recordEvent('CHECKOUT_STARTED');
      const created = await createPaymentIntent({
        items: lines.map((l) => ({ productId: l.id, quantity: l.quantity })),
        method,
        clientKey: clientKeyFor(sessionKey.current, lines, appliedCoupon ?? '', method),
        name: name.trim(),
        contactNumber: contact.trim(),
        email: email.trim(),
        couponCode: appliedCoupon ?? undefined,
        upiId: method === 'UPI' && upiId.trim() ? upiId.trim() : undefined,
      });
      setIntent(created);

      if (method === 'CASH') {
        // Counter flow: the bill is generated at the server-computed amount;
        // the customer pays physically at pickup. No payment is claimed.
        const r = await confirmCheckout({ reference: created.reference, assisted: true });
        finishWithReceipt(r, undefined);
        return;
      }

      setStage('pay');
      if (created.checkout?.sandbox) {
        if (method === 'UPI') {
          try {
            setQr(await getIntentQr(created.reference));
          } catch {
            setQr(null); // payee not configured — the TEST panel still works
          }
        }
      } else {
        await openRazorpay(created);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Payment could not be started.');
      setStage('form');
    } finally {
      setBusy(false);
    }
  };

  const simulateSandbox = async (outcome: 'SUCCESS' | 'FAILED') => {
    if (!intent) return;
    setBusy(true);
    try {
      if (outcome === 'FAILED') {
        await sandboxSettle(intent.reference, 'FAILED');
        setError('The test payment was marked FAILED.');
        setStage('failed');
        return;
      }
      const r = await sandboxSettle(intent.reference, 'SUCCESS');
      finishWithReceipt(r, undefined);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Test settlement failed.');
    } finally {
      setBusy(false);
    }
  };

  const retryPayment = () => {
    sessionKey.current = newSessionKey(); // fresh idempotency session
    setIntent(null);
    setQr(null);
    setError(null);
    setStage('form');
  };

  // ---------------------------------------------------------------- render

  const cashReceipt = receipt?.method === 'CASH';

  return (
    <div className="drawer-overlay" role="dialog" aria-modal="true" aria-label="Checkout" onClick={close}>
      <div className="drawer" onClick={(e) => e.stopPropagation()}>
        {stage === 'done' && receipt ? (
          <div className="checkout-done">
            <div className="receipt">
              <div className="receipt-head">
                <div className="checkout-done-emoji" aria-hidden="true">{cashReceipt ? '🧾' : '✅'}</div>
                <h2 className="receipt-title">{cashReceipt ? 'Order placed!' : 'Payment Successful'}</h2>
                <p className="receipt-sub">
                  {cashReceipt
                    ? 'Pay at the counter and show this bill reference.'
                    : 'Your payment was verified by the backend.'}
                </p>
              </div>
              <div className="receipt-row">
                <span>Order</span>
                <span>{receipt.billUuid ?? '—'}</span>
              </div>
              <div className="receipt-row">
                <span>{cashReceipt ? 'Payable at counter' : 'Amount Paid'}</span>
                <span>{formatMoney(receipt.amount)}</span>
              </div>
              {(receipt.subtotal ?? 0) > 0 && (receipt.discount ?? 0) > 0 && (
                <div className="receipt-row pb-discount">
                  <span>Offer {receipt.couponCode ? `(${receipt.couponCode})` : ''}</span>
                  <span>−{formatMoney(receipt.discount ?? 0)} on {formatMoney(receipt.subtotal ?? 0)}</span>
                </div>
              )}
              <div className="receipt-row">
                <span>Payment Method</span>
                <span>{receipt.method === 'CASH' ? 'Cash at counter' : receipt.method === 'UPI' ? 'UPI' : 'Card'}</span>
              </div>
              {!cashReceipt && (
                <div className="receipt-row">
                  <span>Transaction ID</span>
                  <span>{receipt.transactionId ?? receipt.reference}</span>
                </div>
              )}
            </div>
            <div className="receipt-actions">
              <button type="button" className="btn" onClick={() => { close(); navigate('/my-orders'); }}>
                View Order
              </button>
              <button type="button" className="btn btn-primary" onClick={close}>
                Back to Menu
              </button>
            </div>
            <FeedbackForm billUuid={receipt.billUuid ?? ''} />
          </div>
        ) : stage === 'pay' && intent ? (
          <div className="pay-panel">
            <div className="drawer-head">
              <h2>Complete your payment</h2>
              <button type="button" className="drawer-close" aria-label="Close checkout" onClick={close}>✕</button>
            </div>
            <div className="pay-amount">{formatMoney(intent.total)}</div>
            <div className="pay-ref">Payment reference <code>{intent.reference}</code> · {intent.method === 'UPI' ? 'UPI' : 'Card'}</div>

            {intent.checkout?.sandbox ? (
              <>
                {qr ? (
                  <div>
                    <div className="pay-qr">
                      <img src={qr} alt={`UPI QR code for ${formatMoney(intent.total)}`} />
                    </div>
                    <p className="pay-ref" style={{ marginTop: 8 }}>Scan with any UPI app — amount is fixed at {formatMoney(intent.total)}.</p>
                  </div>
                ) : intent.method === 'UPI' ? (
                  <p className="pay-ref">Merchant UPI QR is not configured on the server (set CAFE_UPI_PAYEE_VPA).</p>
                ) : null}

                <div className="status-chip status-waiting" role="status">
                  <span className="dot" aria-hidden="true" />
                  Waiting for payment…
                </div>

                <div className="sandbox-panel">
                  <div className="sandbox-title">SANDBOX — TEST PAYMENT</div>
                  <p className="sandbox-note">
                    No real payment provider is configured. Use the buttons to simulate the
                    provider webhook and settle this payment for testing.
                  </p>
                  <div className="sandbox-actions">
                    <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void simulateSandbox('SUCCESS')}>
                      Simulate successful payment
                    </button>
                    <button type="button" className="btn" disabled={busy} onClick={() => void simulateSandbox('FAILED')}>
                      Simulate failure
                    </button>
                  </div>
                </div>
              </>
            ) : (
              <div className="status-chip status-waiting" role="status">
                <span className="dot" aria-hidden="true" />
                Waiting for the payment window…
              </div>
            )}

            {error && <p className="field-error" role="alert">{error}</p>}
            <button type="button" className="btn" onClick={retryPayment}>
              ← Change payment method
            </button>
          </div>
        ) : stage === 'failed' ? (
          <div className="pay-panel">
            <div className="drawer-head">
              <h2>Payment failed</h2>
              <button type="button" className="drawer-close" aria-label="Close checkout" onClick={close}>✕</button>
            </div>
            <div className="status-chip status-failed" role="status">Payment failed — Try again</div>
            {error && <p className="field-error" role="alert">{error}</p>}
            <p className="pay-ref">
              No money was charged and no order was created. You can safely retry —
              duplicate attempts are blocked by the server.
            </p>
            <button type="button" className="btn btn-primary" onClick={retryPayment}>Try again</button>
            <button type="button" className="btn" onClick={close}>Back to cart</button>
          </div>
        ) : (
          <form onSubmit={(e: FormEvent) => { e.preventDefault(); void startPayment(); }} noValidate>
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

            {/* Server-authoritative pricing breakdown */}
            {quoteError && <p className="field-error" role="alert">{quoteError}</p>}
            {quote && (
              <div className="price-breakdown" aria-label="Price breakdown">
                <div className="pb-row"><span>Subtotal</span><span>{formatMoney(quote.subtotal)}</span></div>
                {quote.discount > 0 && (
                  <div className="pb-row pb-discount">
                    <span>Discount{quote.couponCode ? ` (${quote.couponCode})` : ''}</span>
                    <span>−{formatMoney(quote.discount)}</span>
                  </div>
                )}
                {quote.tax > 0 && <div className="pb-row"><span>Tax / fees</span><span>{formatMoney(quote.tax)}</span></div>}
                <div className="pb-row pb-total"><span>Payable</span><span>{formatMoney(quote.total)}</span></div>
              </div>
            )}

            {/* Coupon with explicit APPLY — validated + priced by the backend */}
            <div className="field">
              <label htmlFor="co-campaign">Offer code</label>
              {appliedCoupon ? (
                <div className="coupon-applied-chip">
                  <span>✓ {appliedCoupon} applied — you save {formatMoney(quote?.discount ?? 0)}</span>
                  <button type="button" className="linklike" onClick={removeCoupon}>remove</button>
                </div>
              ) : (
                <div className="coupon-row">
                  <input
                    id="co-campaign"
                    className="input"
                    placeholder="e.g. BREW3"
                    value={couponInput}
                    onChange={(e) => setCouponInput(e.target.value.toUpperCase())}
                  />
                  <button type="button" className="btn" disabled={applying || lines.length === 0} onClick={() => void applyCoupon()}>
                    {applying ? 'Checking…' : 'APPLY'}
                  </button>
                </div>
              )}
              {couponError && <span className="field-hint pb-coupon-error" role="alert">{couponError}</span>}
              {!couponError && !appliedCoupon && (
                <span className="field-hint">
                  Codes are validated by the restaurant — the payable amount updates after applying.
                </span>
              )}
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
                <input type="radio" name="pay" checked={method === 'UPI'} onChange={() => setMethod('UPI')} />
                <span>UPI</span>
              </label>
              <label className="menu-toggle">
                <input type="radio" name="pay" checked={method === 'CARD'} onChange={() => setMethod('CARD')} />
                <span>Card</span>
              </label>
              <label className="menu-toggle">
                <input type="radio" name="pay" checked={method === 'CASH'} onChange={() => setMethod('CASH')} />
                <span>Cash at counter</span>
              </label>
              {method === 'UPI' && (
                <div className="field" style={{ marginTop: 8 }}>
                  <label htmlFor="co-upi">UPI ID (optional — for assisted confirmation)</label>
                  <input id="co-upi" className="input" placeholder="name@bank" value={upiId} onChange={(e) => setUpiId(e.target.value)} />
                </div>
              )}
            </fieldset>

            {error && <p className="field-error" role="alert">{error}</p>}

            {requiresLogin ? (
              <button type="submit" className="btn btn-primary" style={{ width: '100%' }}>
                Sign in to place the order →
              </button>
            ) : (
              <button
                type="submit"
                className="btn btn-primary"
                style={{ width: '100%' }}
                disabled={busy || lines.length === 0 || !quote || quote.total <= 0 || !!quoteError}
              >
                {busy ? 'Starting payment…' : method === 'CASH' ? `Place order — ${formatMoney(payable)} (pay at counter)` : `Pay ${formatMoney(payable)}`}
              </button>
            )}
            <p className="field-hint" style={{ textAlign: 'center' }}>
              {cartCount(lines)} item{cartCount(lines) === 1 ? '' : 's'} · amount calculated by the restaurant
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
