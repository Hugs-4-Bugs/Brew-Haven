import { useCallback, useEffect, useRef, useState } from 'react';
import * as api from '../api/cafe';
import {
  priceCart, createPaymentIntent, getIntent, getIntentQr, confirmCheckout, sandboxSettle, cancelIntent,
  type PriceBreakdown, type CheckoutIntent, type CheckoutReceipt, type PayMethod,
} from '../api/checkout';
import { useAuth } from '../auth/useAuth';
import { Button, Card, EmptyState, ErrorState, LoadingState, Modal, ToastStack, useToasts } from '../components/ui';
import { downloadBlob, formatMoney } from '../utils/format';

type PayMethodPos = 'CASH' | 'UPI' | 'UPI_QR' | 'CARD' | 'WALLET';
type Stage = 'cart' | 'checkout' | 'pay' | 'done' | 'failed';

interface CartLine {
  product: api.ProductWrapper;
  quantity: number;
}

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

interface RazorpayInstance { open: () => void; }
interface WindowWithRazorpay extends Window { Razorpay?: new (options: RazorpayOptions) => RazorpayInstance; }

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
  try { return crypto.randomUUID(); } catch { return `s-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`; }
}

/**
 * POS billing — SERVER-PRICED checkout.
 *
 * All totals come from POST /checkout/price (backend pricing engine); the
 * operator never types an amount. Payment methods:
 *  - Cash: intent (method CASH) + assisted confirm -> bill at counter.
 *  - UPI (VPA, counter-assisted): operator attests the payment after seeing it
 *    in the merchant app; payment recorded against the server-priced intent.
 *  - UPI QR: merchant QR for the EXACT server amount + live status polling
 *    (SANDBOX) or the provider window (RAZORPAY).
 *  - Card: provider-hosted checkout (raw card data is never collected here).
 *  - Wallet: legacy counter-assisted deep-link flow (unchanged).
 */
export default function BillingPage(): JSX.Element {
  const { email } = useAuth();
  const { toasts, push, dismiss } = useToasts();

  const [products, setProducts] = useState<api.ProductWrapper[] | null>(null);
  const [categories, setCategories] = useState<api.Category[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [cart, setCart] = useState<CartLine[]>([]);
  const [stage, setStage] = useState<Stage>('cart');
  const [customer, setCustomer] = useState({ name: '', contactNumber: '', email: '' });
  const [method, setMethod] = useState<PayMethodPos>('CASH');

  // server pricing + coupon
  const [quote, setQuote] = useState<PriceBreakdown | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [couponInput, setCouponInput] = useState('');
  const [appliedCoupon, setAppliedCoupon] = useState<string | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);

  // payment state
  const [upiId, setUpiId] = useState('');
  const [intent, setIntent] = useState<CheckoutIntent | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<CheckoutReceipt | null>(null);
  const [walletUrl, setWalletUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const sessionKey = useRef(newSessionKey());

  const load = useCallback(async () => {
    setError(null);
    try {
      const [p, c] = await Promise.all([api.getProducts(), api.getCategories()]);
      setProducts(p);
      setCategories(c);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load menu.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Server pricing whenever the cart changes (amounts are never client-computed).
  useEffect(() => {
    let cancelled = false;
    const run = async (): Promise<void> => {
      if (cart.length === 0) {
        if (!cancelled) { setQuote(null); setQuoteError(null); }
        return;
      }
      try {
        const q = await priceCart(
          cart.map((l) => ({ productId: l.product.id, quantity: l.quantity })),
          appliedCoupon ?? undefined,
          customer.email || email || undefined,
        );
        if (!cancelled) { setQuote(q); setQuoteError(null); }
      } catch (err) {
        if (!cancelled) setQuoteError(err instanceof Error ? err.message : 'Pricing failed.');
      }
    };
    void run();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cart, appliedCoupon]);

  // Live status polling while a SANDBOX payment is on screen.
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
            reference: st.reference, status: 'SUCCESS',
            transactionId: st.providerPaymentId ?? st.reference,
            amount: st.total, method: st.method, billUuid: st.billUuid ?? null,
          });
          setStage('done');
          push('ok', 'Payment received.');
        } else if (st.status === 'FAILED' || st.status === 'EXPIRED' || st.status === 'CANCELLED') {
          setStage('failed');
          push('err', 'Payment did not complete.');
        }
      } catch { /* keep polling */ }
    };
    const timer = window.setInterval(poll, 2500);
    return () => { stopped = true; window.clearInterval(timer); };
  }, [stage, intent, push]);

  const available = (products ?? []).filter((p) => p.status === 'true');
  const visible = selectedCategory === 'all' ? available : available.filter((p) => String(p.categoryId) === selectedCategory);
  const payable = quote?.total ?? 0;

  const addToCart = (product: api.ProductWrapper): void => {
    setCart((prev) => {
      const existing = prev.find((l) => l.product.id === product.id);
      if (existing) {
        return prev.map((l) => (l.product.id === product.id ? { ...l, quantity: l.quantity + 1 } : l));
      }
      return [...prev, { product, quantity: 1 }];
    });
  };

  const setQty = (productId: number, quantity: number): void => {
    setCart((prev) => (quantity <= 0 ? prev.filter((l) => l.product.id !== productId) : prev.map((l) => (l.product.id === productId ? { ...l, quantity } : l))));
  };

  // ---------------------------------------------------------------- coupon (server-validated)

  const applyCoupon = async (): Promise<void> => {
    const code = couponInput.trim();
    if (!code) { setCouponError('Enter an offer code first.'); return; }
    if (cart.length === 0) { setCouponError('Add items to the cart first.'); return; }
    setApplying(true);
    setCouponError(null);
    try {
      const q = await priceCart(
        cart.map((l) => ({ productId: l.product.id, quantity: l.quantity })),
        code,
        customer.email || email || undefined,
      );
      setQuote(q);
      if (q.couponApplied) {
        setAppliedCoupon(q.couponCode ?? code);
        push('ok', `Offer ${q.couponCode} applied — ${formatMoney(q.discount)} off.`);
      } else {
        setAppliedCoupon(null);
        setCouponError(q.couponRejectionReason ?? 'This coupon could not be applied.');
      }
    } catch (err) {
      setCouponError(err instanceof Error ? err.message : 'Coupon check failed.');
    } finally {
      setApplying(false);
    }
  };

  const removeCoupon = (): void => {
    setCouponInput('');
    setCouponError(null);
    setAppliedCoupon(null);
  };

  // ---------------------------------------------------------------- payment actions

  const clientKey = (m: PayMethod): string => {
    const sig = cart.slice().sort((a, b) => a.product.id - b.product.id).map((l) => `${l.product.id}x${l.quantity}`).join(',');
    return `${sessionKey.current}:${m}:${appliedCoupon || '-'}:${sig}`;
  };

  const createIntent = async (m: PayMethod): Promise<CheckoutIntent> => {
    if (!customer.name.trim() || !customer.contactNumber.trim() || !customer.email.trim()) {
      throw new Error('Fill the customer name, contact and email first.');
    }
    if (!quote) throw new Error(quoteError ?? 'Cart is not priced yet.');
    return createPaymentIntent({
      items: cart.map((l) => ({ productId: l.product.id, quantity: l.quantity })),
      method: m,
      clientKey: clientKey(m),
      name: customer.name.trim(),
      contactNumber: customer.contactNumber.trim(),
      email: customer.email.trim(),
      couponCode: appliedCoupon ?? undefined,
      upiId: m === 'UPI' && upiId.trim() ? upiId.trim() : undefined,
    });
  };

  const finish = (r: CheckoutReceipt): void => {
    setReceipt(r);
    setStage('done');
    push('ok', 'Payment completed and bill generated.');
  };

  const payCash = async (): Promise<void> => {
    setBusy(true);
    try {
      const created = await createIntent('CASH');
      const r = await confirmCheckout({ reference: created.reference, assisted: true });
      finish(r);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Cash bill failed.');
    } finally {
      setBusy(false);
    }
  };

  const payUpiAssisted = async (): Promise<void> => {
    setBusy(true);
    try {
      const created = await createIntent('UPI');
      const r = await confirmCheckout({ reference: created.reference, assisted: true });
      finish(r);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Assisted UPI confirmation failed.');
    } finally {
      setBusy(false);
    }
  };

  const openProviderWindow = async (created: CheckoutIntent): Promise<void> => {
    const ok = await loadRazorpayScript();
    const w = window as WindowWithRazorpay;
    if (!ok || !w.Razorpay || !created.checkout?.keyId) {
      push('err', 'Could not open the payment window.');
      setStage('checkout');
      return;
    }
    const rzp = new w.Razorpay({
      key: created.checkout.keyId,
      amount: created.checkout.amountPaise,
      currency: created.checkout.currency,
      name: 'Brew Haven',
      description: `Order ${created.reference}`,
      order_id: created.checkout.providerOrderId ?? '',
      prefill: { name: customer.name, email: customer.email, contact: customer.contactNumber },
      theme: { color: '#b45309' },
      handler: (resp) => {
        confirmCheckout({
          reference: created.reference,
          providerOrderId: resp.razorpay_order_id,
          providerPaymentId: resp.razorpay_payment_id,
          signature: resp.razorpay_signature,
        })
          .then(finish)
          .catch((err) => {
            push('err', err instanceof Error ? err.message : 'Payment verification failed.');
            setStage('failed');
          });
      },
      modal: { ondismiss: () => setStage('checkout') },
    });
    rzp.open();
  };

  const startUpiQr = async (): Promise<void> => {
    setBusy(true);
    setQr(null);
    try {
      const created = await createIntent('UPI');
      setIntent(created);
      setStage('pay');
      if (!created.checkout?.sandbox) {
        await openProviderWindow(created);
        return;
      }
      try {
        setQr(await getIntentQr(created.reference));
      } catch {
        setQr(null);
        push('err', 'Merchant UPI QR is not configured (set CAFE_UPI_PAYEE_VPA).');
      }
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'QR payment failed.');
    } finally {
      setBusy(false);
    }
  };

  const startCard = async (): Promise<void> => {
    setBusy(true);
    try {
      const created = await createIntent('CARD');
      setIntent(created);
      if (!created.checkout?.sandbox) {
        await openProviderWindow(created);
        return;
      }
      setStage('pay');
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Card payment failed.');
    } finally {
      setBusy(false);
    }
  };

  const confirmQrPaid = async (): Promise<void> => {
    if (!intent) return;
    setBusy(true);
    try {
      const r = await confirmCheckout({ reference: intent.reference, assisted: true });
      finish(r);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Assisted confirmation failed.');
    } finally {
      setBusy(false);
    }
  };

  const simulateSandbox = async (outcome: 'SUCCESS' | 'FAILED'): Promise<void> => {
    if (!intent) return;
    setBusy(true);
    try {
      if (outcome === 'FAILED') {
        await sandboxSettle(intent.reference, 'FAILED');
        setStage('failed');
        push('err', 'Test payment marked FAILED.');
        return;
      }
      const r = await sandboxSettle(intent.reference, 'SUCCESS');
      finish(r);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Test settlement failed.');
    } finally {
      setBusy(false);
    }
  };

  const payWallet = async (): Promise<void> => {
    setBusy(true);
    setWalletUrl(null);
    try {
      const url = await api.payWithWallet('phonepe', payable);
      setWalletUrl(url);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Wallet payment failed.');
    } finally {
      setBusy(false);
    }
  };

  const confirmWalletPaid = async (): Promise<void> => {
    setBusy(true);
    try {
      await api.recordWalletPayment({
        userId: email ?? 'pos-user',
        amount: payable,
        walletType: 'phonepe',
        provider: 'PHONEPE',
      });
      push('ok', 'Wallet payment recorded (counter-assisted).');
      setStage('checkout');
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Wallet recording failed.');
    } finally {
      setBusy(false);
    }
  };

  // ---------------------------------------------------------------- bill pdf + reset

  const downloadPdf = async (): Promise<void> => {
    if (!receipt?.billUuid) return;
    setBusy(true);
    try {
      const blob = await api.downloadBillPdf(receipt.billUuid);
      downloadBlob(blob, `${receipt.billUuid}.pdf`);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'PDF download failed.');
    } finally {
      setBusy(false);
    }
  };

  const startNewBill = (): void => {
    setCart([]);
    setStage('cart');
    setCustomer({ name: '', contactNumber: '', email: '' });
    setMethod('CASH');
    setQuote(null);
    setCouponInput('');
    setAppliedCoupon(null);
    setCouponError(null);
    setIntent(null);
    setQr(null);
    setReceipt(null);
    setWalletUrl(null);
    setUpiId('');
    sessionKey.current = newSessionKey();
  };

  const closePayStage = (): void => {
    if (intent && stage === 'pay') void cancelIntent(intent.reference).catch(() => undefined);
    setIntent(null);
    setQr(null);
    setStage('checkout');
  };

  // ---------------------------------------------------------------- render

  const menuTitle = selectedCategory === 'all'
    ? 'Menu'
    : `Menu — ${categories.find((c) => String(c.id) === selectedCategory)?.name ?? ''}`;

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!products) return <LoadingState label="Loading menu…" />;

  if (stage === 'done' && receipt) {
    return (
      <Card className="card" title="Bill completed">
        <div className="state-box">
          <div className="state-ico" aria-hidden="true">{receipt.method === 'CASH' ? '🧾' : '✅'}</div>
          <h3>{receipt.method === 'CASH' ? 'Order placed — pay at counter' : 'Payment Successful'}</h3>
          <div className="receipt" style={{ maxWidth: 420, margin: '10px auto' }}>
            <div className="receipt-row"><span>Bill</span><span>{receipt.billUuid ?? '—'}</span></div>
            <div className="receipt-row"><span>{receipt.method === 'CASH' ? 'Payable at counter' : 'Amount Paid'}</span><span>{formatMoney(receipt.amount)}</span></div>
            {(receipt.discount ?? 0) > 0 && (
              <div className="receipt-row pb-discount">
                <span>Offer {receipt.couponCode ? `(${receipt.couponCode})` : ''}</span>
                <span>−{formatMoney(receipt.discount ?? 0)} on {formatMoney(receipt.subtotal ?? 0)}</span>
              </div>
            )}
            <div className="receipt-row"><span>Payment Method</span><span>{receipt.method}</span></div>
            <div className="receipt-row"><span>Transaction ID</span><span>{receipt.transactionId ?? receipt.reference}</span></div>
          </div>
          <div className="row mt-2" style={{ justifyContent: 'center' }}>
            <Button variant="primary" onClick={() => void downloadPdf()} loading={busy} disabled={!receipt.billUuid}>
              ⬇ Download PDF
            </Button>
            <Button onClick={startNewBill}>Start new bill</Button>
          </div>
        </div>
        <ToastStack toasts={toasts} dismiss={dismiss} />
      </Card>
    );
  }

  return (
    <>
      <div className="pos-layout">
        {/* ------------------------------------------------- menu */}
        <Card title={menuTitle}>
          <div className="row mb-2">
            <select
              className="select"
              style={{ width: 200 }}
              aria-label="Filter menu by category"
              value={selectedCategory}
              onChange={(e) => setSelectedCategory(e.target.value)}
            >
              <option value="all">All categories</option>
              {categories.map((c) => (
                <option key={c.id} value={String(c.id)}>{c.name}</option>
              ))}
            </select>
            <span className="muted">{visible.length} available item{visible.length === 1 ? '' : 's'}</span>
          </div>
          {visible.length === 0 ? (
            <EmptyState ico="🍽️" title="Nothing available in this category" hint="Mark products available or import a menu." />
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: 10 }}>
              {visible.map((p) => (
                <button key={p.id} type="button" className="pos-item" onClick={() => addToCart(p)}>
                  <span className="pos-item-name">{p.name}</span>
                  <span className="pos-item-meta">{p.categoryName}</span>
                  <span className="pos-item-price">{formatMoney(p.price)}</span>
                </button>
              ))}
            </div>
          )}
        </Card>

        {/* ------------------------------------------------- cart + checkout */}
        <Card title={'Cart (' + cart.length + ')'}>
          {cart.length === 0 ? (
            <EmptyState ico="🛒" title="Cart is empty" hint="Tap menu items to add them." />
          ) : (
            <>
              {cart.map((line) => (
                <div className="cart-line" key={line.product.id}>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 700 }}>{line.product.name}</div>
                    <div className="muted">{formatMoney(line.product.price)} each</div>
                  </div>
                  <div className="qty-controls">
                    <button type="button" className="qty-btn" aria-label={`Decrease ${line.product.name}`} onClick={() => setQty(line.product.id, line.quantity - 1)}>−</button>
                    <strong aria-label={`Quantity of ${line.product.name}`}>{line.quantity}</strong>
                    <button type="button" className="qty-btn" aria-label={`Increase ${line.product.name}`} onClick={() => setQty(line.product.id, line.quantity + 1)}>＋</button>
                  </div>
                  <strong style={{ width: 74, textAlign: 'right' }}>{formatMoney((line.product.price ?? 0) * line.quantity)}</strong>
                </div>
              ))}

              {/* Server-authoritative pricing */}
              {quoteError && <p className="field-error" role="alert">{quoteError}</p>}
              {quote && (
                <div className="price-breakdown mt-2" aria-label="Price breakdown">
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

              <Button variant="primary" className="mt-2" style={{ width: '100%' }} onClick={() => setStage('checkout')} disabled={!quote || !!quoteError}>
                Proceed to checkout →
              </Button>
            </>
          )}
        </Card>
      </div>

      {/* ------------------------------------------------- checkout modal */}
      {stage === 'checkout' && (
        <Modal title="Checkout" onClose={() => setStage('cart')}>
          <div className="field">
            <label htmlFor="co-name">Customer name</label>
            <input id="co-name" className="input" value={customer.name} onChange={(e) => setCustomer({ ...customer, name: e.target.value })} />
          </div>
          <div className="form-row">
            <div className="field">
              <label htmlFor="co-contact">Contact number</label>
              <input id="co-contact" className="input" inputMode="tel" value={customer.contactNumber} onChange={(e) => setCustomer({ ...customer, contactNumber: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="co-email">Email</label>
              <input id="co-email" className="input" type="email" value={customer.email} onChange={(e) => setCustomer({ ...customer, email: e.target.value })} />
            </div>
          </div>

          {/* Coupon with explicit APPLY — validated + priced server-side */}
          <div className="field">
            <label htmlFor="co-coupon">Offer code</label>
            {appliedCoupon ? (
              <div className="coupon-applied-chip">
                <span>✓ {appliedCoupon} applied — {formatMoney(quote?.discount ?? 0)} off</span>
                <button type="button" className="linklike" onClick={removeCoupon}>remove</button>
              </div>
            ) : (
              <div className="coupon-row">
                <input
                  id="co-coupon"
                  className="input"
                  placeholder="e.g. BREW3"
                  value={couponInput}
                  onChange={(e) => setCouponInput(e.target.value.toUpperCase())}
                />
                <Button size="sm" onClick={() => void applyCoupon()} loading={applying} disabled={cart.length === 0}>
                  APPLY
                </Button>
              </div>
            )}
            {couponError && <span className="field-hint pb-coupon-error" role="alert">{couponError}</span>}
          </div>

          <div className="field">
            <label htmlFor="co-method">Payment method</label>
            <select id="co-method" className="select" value={method} onChange={(e) => { setMethod(e.target.value as PayMethodPos); setWalletUrl(null); }}>
              <option value="CASH">Cash</option>
              <option value="UPI">UPI — counter-assisted (VPA)</option>
              <option value="UPI_QR">UPI — QR / payment window</option>
              <option value="CARD">Card — provider checkout</option>
              <option value="WALLET">Wallet (legacy counter-assisted)</option>
            </select>
          </div>

          {method === 'UPI' && (
            <div className="card" style={{ background: 'var(--bg-2)' }}>
              <div className="field" style={{ marginBottom: 8 }}>
                <label htmlFor="upi-id">Customer UPI ID (VPA — optional, for the record)</label>
                <input
                  id="upi-id"
                  className="input"
                  placeholder="name@bank"
                  value={upiId}
                  onChange={(e) => setUpiId(e.target.value)}
                />
              </div>
              <p className="muted" style={{ fontSize: 13 }}>
                Counter-assisted: confirm after the payment appears in the merchant UPI app.
                The amount is fixed at the server-computed {formatMoney(payable)}.
              </p>
              <Button size="sm" variant="primary" onClick={() => void payUpiAssisted()} loading={busy} disabled={!quote}>
                Customer paid — confirm {formatMoney(payable)}
              </Button>
            </div>
          )}

          {method === 'UPI_QR' && (
            <div className="qr-box">
              <p className="muted">
                Starts a payment intent for the exact server amount ({formatMoney(payable)}), then shows the
                merchant QR (sandbox) or opens the provider payment window.
              </p>
              <Button variant="primary" onClick={() => void startUpiQr()} loading={busy} disabled={!quote}>
                Start UPI payment — {formatMoney(payable)}
              </Button>
            </div>
          )}

          {method === 'CARD' && (
            <div className="card" style={{ background: 'var(--bg-2)' }}>
              <p className="muted" style={{ fontSize: 13 }}>
                Card details are collected by the payment provider's secure checkout —
                raw card numbers and CVV are never entered into or stored by this system.
              </p>
              <Button size="sm" variant="primary" onClick={() => void startCard()} loading={busy} disabled={!quote}>
                Charge card — {formatMoney(payable)}
              </Button>
            </div>
          )}

          {method === 'WALLET' && (
            <div className="card" style={{ background: 'var(--bg-2)' }}>
              <p className="muted" style={{ fontSize: 13 }}>
                Legacy counter-assisted wallet flow (deep-link + operator record).
              </p>
              {walletUrl ? (
                <>
                  <p className="mono" style={{ wordBreak: 'break-all' }}>{walletUrl}</p>
                  <Button size="sm" variant="primary" onClick={() => void confirmWalletPaid()} loading={busy}>
                    Customer paid — confirm
                  </Button>
                </>
              ) : (
                <Button size="sm" onClick={() => void payWallet()} loading={busy} disabled={!quote}>
                  Generate wallet link
                </Button>
              )}
            </div>
          )}

          {method === 'CASH' && (
            <div className="card" style={{ background: 'var(--bg-2)' }}>
              <p className="muted" style={{ fontSize: 13 }}>
                Generates the bill at the server-computed amount ({formatMoney(payable)}); the customer
                pays physically at the counter. No gateway payment is claimed.
              </p>
              <Button size="sm" variant="primary" onClick={() => void payCash()} loading={busy} disabled={!quote}>
                Create cash bill — {formatMoney(payable)}
              </Button>
            </div>
          )}

          <div className="modal-actions">
            <Button variant="ghost" onClick={() => setStage('cart')}>Back to cart</Button>
          </div>
        </Modal>
      )}

      {/* ------------------------------------------------- pay / waiting modal */}
      {stage === 'pay' && intent && (
        <Modal title="Payment in progress" onClose={closePayStage}>
          <div className="pay-panel">
            <div className="pay-amount">{formatMoney(intent.total)}</div>
            <div className="pay-ref">Reference <code>{intent.reference}</code> · {intent.method}</div>

            {qr && (
              <div>
                <div className="pay-qr" style={{ margin: '0 auto' }}>
                  <img src={qr} alt={`UPI QR for ${formatMoney(intent.total)}`} />
                </div>
                <p className="muted" style={{ fontSize: 13 }}>Amount is fixed at {formatMoney(intent.total)} — scan with any UPI app.</p>
              </div>
            )}

            <div className="status-chip status-waiting" role="status">
              <span className="dot" aria-hidden="true" />
              Waiting for payment…
            </div>

            {intent.checkout?.sandbox && (
              <div className="sandbox-panel">
                <div className="sandbox-title">SANDBOX — TEST PAYMENT</div>
                <p className="sandbox-note">
                  No real provider is configured. Simulate the provider webhook outcome for testing.
                </p>
                <div className="sandbox-actions">
                  <Button size="sm" variant="primary" onClick={() => void simulateSandbox('SUCCESS')} loading={busy}>
                    Simulate success
                  </Button>
                  <Button size="sm" onClick={() => void simulateSandbox('FAILED')} loading={busy}>
                    Simulate failure
                  </Button>
                </div>
              </div>
            )}

            <Button size="sm" onClick={() => void confirmQrPaid()} loading={busy}>
              Customer paid — staff confirm (assisted)
            </Button>
          </div>
        </Modal>
      )}

      {/* ------------------------------------------------- failed modal */}
      {stage === 'failed' && (
        <Modal title="Payment failed" onClose={() => setStage('checkout')}>
          <div className="pay-panel">
            <div className="status-chip status-failed" role="status">Payment failed — Try again</div>
            <p className="muted" style={{ fontSize: 13 }}>
              No money was captured and no bill was created. Duplicate attempts are blocked by the server.
            </p>
            <Button variant="primary" onClick={() => { sessionKey.current = newSessionKey(); setIntent(null); setQr(null); setStage('checkout'); }}>
              Back to checkout
            </Button>
          </div>
        </Modal>
      )}

      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
