import { useCallback, useEffect, useMemo, useState } from 'react';
import * as api from '../api/cafe';
import { useAuth } from '../auth/useAuth';
import { Button, Card, EmptyState, ErrorState, LoadingState, Modal, ToastStack, useToasts } from '../components/ui';
import { downloadBlob, formatMoney } from '../utils/format';

type PayMethod = 'CASH' | 'UPI' | 'UPI_QR' | 'CARD' | 'WALLET';
type Stage = 'cart' | 'checkout' | 'paying' | 'done';

interface CartLine {
  product: api.ProductWrapper;
  quantity: number;
}

const UPI_KEY = 'cafe.upi.remembered';

function loadRememberedUpi(): string {
  try {
    return localStorage.getItem(UPI_KEY) ?? '';
  } catch {
    return '';
  }
}

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
  const [method, setMethod] = useState<PayMethod>('CASH');

  // payment state
  const [upiId, setUpiId] = useState(loadRememberedUpi());
  const [rememberUpi, setRememberUpi] = useState(!!loadRememberedUpi());
  const [upiVerified, setUpiVerified] = useState(false);
  const [qrDataUri, setQrDataUri] = useState<string | null>(null);
  const [card, setCard] = useState({ cardNumber: '', cardHolderName: '', cardExpiry: '', cvv: '' });
  const [walletType, setWalletType] = useState('phonepe');
  const [walletUrl, setWalletUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [paid, setPaid] = useState<string | null>(null); // transaction id / 'cash'
  const [billUuid, setBillUuid] = useState<string | null>(null);
  const [paymentDone, setPaymentDone] = useState(false); // duplicate-payment guard

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

  const available = useMemo(() => (products ?? []).filter((p) => p.status === 'true'), [products]);
  const visible = useMemo(
    () => (selectedCategory === 'all' ? available : available.filter((p) => String(p.categoryId) === selectedCategory)),
    [available, selectedCategory],
  );

  const total = useMemo(() => cart.reduce((sum, l) => sum + (l.product.price ?? 0) * l.quantity, 0), [cart]);

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

  // ---------------------------------------------------------------- payment actions

  const verifyUpi = async (): Promise<void> => {
    if (!upiId.trim()) {
      push('err', 'Enter a UPI ID first.');
      return;
    }
    setBusy(true);
    try {
      const result = await api.verifyUpiId(upiId.trim());
      if (result.valid) {
        setUpiVerified(true);
        push('ok', 'UPI ID verified.');
      } else {
        setUpiVerified(false);
        push('err', 'This UPI ID does not look valid. Check the format (e.g. name@bank).');
      }
    } finally {
      setBusy(false);
    }
  };

  const payUpi = async (): Promise<void> => {
    setBusy(true);
    try {
      const message = await api.payWithUpi({
        userId: email ?? 'pos-user',
        upiId: upiId.trim(),
        amount: total,
        provider: 'UPI',
      });
      if (rememberUpi) {
        try {
          localStorage.setItem(UPI_KEY, upiId.trim());
        } catch { /* ignore */ }
      } else {
        try {
          localStorage.removeItem(UPI_KEY);
        } catch { /* ignore */ }
      }
      setPaid(message);
      setPaymentDone(true);
      push('ok', 'UPI payment recorded.');
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'UPI payment failed.');
    } finally {
      setBusy(false);
    }
  };

  const showQr = async (): Promise<void> => {
    setBusy(true);
    setQrDataUri(null);
    try {
      const uri = await api.getUpiQr(total);
      setQrDataUri(uri);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'QR generation failed.');
    } finally {
      setBusy(false);
    }
  };

  const confirmQrPaid = async (): Promise<void> => {
    // QR payment confirmation records a UPI payment against the cafe's configured
    // merchant payee. The POS convention marker VPA 'merchant-qr@cafe' is format-valid
    // (backend validates NPCI format) and clearly labels QR-sourced payments; the
    // actual payee encoded in the QR is the server-configured merchant VPA.
    setBusy(true);
    try {
      const message = await api.payWithUpi({
        userId: email ?? 'pos-user',
        upiId: 'merchant-qr@cafe',
        amount: total,
        provider: 'UPI-QR',
      });
      setPaid(message);
      setPaymentDone(true);
      push('ok', 'QR payment recorded after customer confirmation.');
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Payment recording failed.');
    } finally {
      setBusy(false);
    }
  };

  const payCard = async (): Promise<void> => {
    if (!/^\d{12,19}$/.test(card.cardNumber)) {
      push('err', 'Card number must be 12-19 digits.');
      return;
    }
    if (!card.cardHolderName.trim() || !card.cardExpiry.trim()) {
      push('err', 'Card holder name and expiry are required.');
      return;
    }
    setBusy(true);
    try {
      const saved = await api.payWithCard({
        userId: email ?? 'pos-user',
        amount: total,
        provider: 'CARD',
        cardNumber: card.cardNumber,
        cardHolderName: card.cardHolderName,
        cardExpiry: card.cardExpiry,
        cvv: card.cvv,
      });
      setPaid(saved?.transactionId ?? 'card');
      setPaymentDone(true);
      push('ok', 'Card payment recorded (card number stored masked).');
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Card payment failed.');
    } finally {
      setBusy(false);
    }
  };

  const payWallet = async (): Promise<void> => {
    setBusy(true);
    setWalletUrl(null);
    try {
      const url = await api.payWithWallet(walletType, total);
      setWalletUrl(url);
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Wallet payment failed.');
    } finally {
      setBusy(false);
    }
  };

  const confirmWalletPaid = async (): Promise<void> => {
    // The dedicated wallet endpoint returns only the deep-link URL; the payment record
    // is persisted via the generic /api/payment/process endpoint (backend derives
    // paymentType=WALLET for plain Payment payloads and stores walletType).
    setBusy(true);
    try {
      const saved = await api.recordWalletPayment({
        userId: email ?? 'pos-user',
        amount: total,
        walletType,
        provider: walletType.toUpperCase(),
      });
      setPaid(saved?.transactionId ?? 'wallet');
      setPaymentDone(true);
      push('ok', 'Wallet payment recorded.');
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Wallet recording failed.');
    } finally {
      setBusy(false);
    }
  };

  // ---------------------------------------------------------------- bill

  const generateBill = async (): Promise<void> => {
    setBusy(true);
    try {
      const productDetails = JSON.stringify(
        cart.map((l) => ({
          id: l.product.id,
          name: l.product.name,
          category: l.product.categoryName ?? '',
          quantity: l.quantity,
          price: l.product.price ?? 0,
          total: (l.product.price ?? 0) * l.quantity,
        })),
      );
      const result = await api.generateBill({
        name: customer.name.trim(),
        contactNumber: customer.contactNumber.trim(),
        email: customer.email.trim(),
        paymentMethod: method === 'CASH' ? 'Cash' : method === 'UPI_QR' ? 'UPI' : method,
        productDetails,
        totalAmount: total,
        isGenerate: true,
      });
      setBillUuid(result.uuid.trim());
      setStage('done');
      push('ok', 'Bill generated.');
    } catch (err) {
      push('err', err instanceof Error ? err.message : 'Bill generation failed.');
    } finally {
      setBusy(false);
    }
  };

  const downloadPdf = async (): Promise<void> => {
    if (!billUuid) return;
    setBusy(true);
    try {
      const blob = await api.downloadBillPdf(billUuid);
      downloadBlob(blob, `${billUuid}.pdf`);
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
    setUpiVerified(false);
    setQrDataUri(null);
    setWalletUrl(null);
    setPaid(null);
    setBillUuid(null);
    setPaymentDone(false);
    setCard({ cardNumber: '', cardHolderName: '', cardExpiry: '', cvv: '' });
  };

  // ---------------------------------------------------------------- render

  const menuTitle = useMemo(() => {
    if (selectedCategory === 'all') return 'Menu';
    const cat = categories.find((c) => String(c.id) === selectedCategory);
    return cat ? `Menu — ${cat.name}` : 'Menu';
  }, [selectedCategory, categories]);

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!products) return <LoadingState label="Loading menu…" />;

  if (stage === 'done') {
    return (
      <Card className="card" title="Bill completed">
        <div className="state-box">
          <div className="state-ico" aria-hidden="true">✅</div>
          <h3>Bill {billUuid} generated</h3>
          <p>
            Total <strong>{formatMoney(total)}</strong> · {paid === 'cash' ? 'Paid by cash' : `Payment recorded (${paid})`}
          </p>
          <div className="row mt-2" style={{ justifyContent: 'center' }}>
            <Button variant="primary" onClick={() => void downloadPdf()} loading={busy}>
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
              <div className="spread mt-2" style={{ borderTop: '1px solid var(--border)', paddingTop: 14 }}>
                <span style={{ fontWeight: 700 }}>Total</span>
                <span style={{ fontSize: 22, fontWeight: 800, color: 'var(--accent-strong)' }}>{formatMoney(total)}</span>
              </div>
              <Button variant="primary" className="mt-2" style={{ width: '100%' }} onClick={() => setStage('checkout')}>
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
          <div className="field">
            <label htmlFor="co-method">Payment method</label>
            <select id="co-method" className="select" value={method} onChange={(e) => { setMethod(e.target.value as PayMethod); setUpiVerified(false); setQrDataUri(null); setWalletUrl(null); }}>
              <option value="CASH">Cash</option>
              <option value="UPI">UPI — VPA</option>
              <option value="UPI_QR">UPI — QR code</option>
              <option value="CARD">Card</option>
              <option value="WALLET">Wallet (PhonePe / Paytm / GPay)</option>
            </select>
          </div>

          {method === 'UPI' && (
            <div className="card" style={{ background: 'var(--bg-2)' }}>
              <div className="field" style={{ marginBottom: 8 }}>
                <label htmlFor="upi-id">UPI ID (VPA)</label>
                <input
                  id="upi-id"
                  className="input"
                  placeholder="name@bank"
                  value={upiId}
                  onChange={(e) => { setUpiId(e.target.value); setUpiVerified(false); }}
                />
              </div>
              <label className="row mb-1" style={{ cursor: 'pointer', fontSize: 13 }}>
                <input type="checkbox" checked={rememberUpi} onChange={(e) => setRememberUpi(e.target.checked)} />
                Remember this UPI ID on this device
              </label>
              <div className="row">
                <Button size="sm" onClick={() => void verifyUpi()} loading={busy} disabled={upiVerified}>
                  {upiVerified ? '✓ Verified' : 'Verify'}
                </Button>
                <Button size="sm" variant="primary" onClick={() => void payUpi()} loading={busy} disabled={!upiVerified || paymentDone}>
                  Pay {formatMoney(total)}
                </Button>
              </div>
              {upiVerified && <p className="muted mt-1">Verified — the same UPI ID can be reused for future payments.</p>}
            </div>
          )}

          {method === 'UPI_QR' && (
            <div className="qr-box">
              <p className="muted">QR encodes the cafe's configured merchant UPI — scan with any UPI app.</p>
              {qrDataUri ? (
                <>
                  <img src={qrDataUri} alt="UPI payment QR code" />
                  <div className="row mt-2" style={{ justifyContent: 'center' }}>
                    <Button size="sm" onClick={() => void showQr()} loading={busy}>Regenerate</Button>
                    <Button size="sm" variant="primary" onClick={() => void confirmQrPaid()} loading={busy} disabled={paymentDone}>
                      Customer paid — confirm
                    </Button>
                  </div>
                </>
              ) : (
                <Button variant="primary" onClick={() => void showQr()} loading={busy} disabled={paymentDone}>
                  Show QR for {formatMoney(total)}
                </Button>
              )}
            </div>
          )}

          {method === 'CARD' && (
            <div className="card" style={{ background: 'var(--bg-2)' }}>
              <div className="field" style={{ marginBottom: 8 }}>
                <label htmlFor="cc-num">Card number (12–19 digits)</label>
                <input id="cc-num" className="input" inputMode="numeric" value={card.cardNumber} onChange={(e) => setCard({ ...card, cardNumber: e.target.value.replace(/\D/g, '') })} />
              </div>
              <div className="field" style={{ marginBottom: 8 }}>
                <label htmlFor="cc-name">Card holder name</label>
                <input id="cc-name" className="input" value={card.cardHolderName} onChange={(e) => setCard({ ...card, cardHolderName: e.target.value })} />
              </div>
              <div className="form-row">
                <div className="field" style={{ marginBottom: 8 }}>
                  <label htmlFor="cc-exp">Expiry (MM/YY)</label>
                  <input id="cc-exp" className="input" placeholder="12/28" value={card.cardExpiry} onChange={(e) => setCard({ ...card, cardExpiry: e.target.value })} />
                </div>
                <div className="field" style={{ marginBottom: 8 }}>
                  <label htmlFor="cc-cvv">CVV</label>
                  <input id="cc-cvv" className="input" inputMode="numeric" type="password" value={card.cvv} onChange={(e) => setCard({ ...card, cvv: e.target.value.replace(/\D/g, '').slice(0, 4) })} />
                </div>
              </div>
              <p className="muted">Only the masked number (last 4 digits) is stored. CVV is never persisted.</p>
              <Button size="sm" variant="primary" className="mt-1" onClick={() => void payCard()} loading={busy} disabled={paymentDone}>
                Charge {formatMoney(total)}
              </Button>
            </div>
          )}

          {method === 'WALLET' && (
            <div className="card" style={{ background: 'var(--bg-2)' }}>
              <div className="field" style={{ marginBottom: 8 }}>
                <label htmlFor="w-type">Wallet</label>
                <select id="w-type" className="select" value={walletType} onChange={(e) => setWalletType(e.target.value)}>
                  <option value="phonepe">PhonePe</option>
                  <option value="paytm">Paytm</option>
                  <option value="googlepay">Google Pay</option>
                </select>
              </div>
              {walletUrl ? (
                <>
                  <p className="muted">Deep-link generated. Open it in the wallet app, then confirm once paid:</p>
                  <p className="mono" style={{ wordBreak: 'break-all' }}>{walletUrl}</p>
                  <Button size="sm" variant="primary" onClick={() => void confirmWalletPaid()} loading={busy} disabled={paymentDone}>
                    Customer paid — confirm
                  </Button>
                </>
              ) : (
                <Button size="sm" onClick={() => void payWallet()} loading={busy} disabled={paymentDone}>
                  Generate wallet link
                </Button>
              )}
            </div>
          )}

          {method === 'CASH' && (
            <p className="muted">Cash payment — the bill is generated without a payment-gateway step.</p>
          )}

          {paymentDone && (
            <p style={{ color: 'var(--success)', fontSize: 13.5 }}>
              ✓ Payment recorded{paid && paid !== 'cash' ? ` (${paid})` : ''}. Duplicate payment for this bill is blocked.
            </p>
          )}

          <div className="modal-actions">
            <Button variant="ghost" onClick={() => setStage('cart')}>Back to cart</Button>
            <Button
              variant="primary"
              loading={busy}
              disabled={cart.length === 0 || (!paymentDone && method !== 'CASH')}
              onClick={() => void generateBill()}
            >
              Generate bill
            </Button>
          </div>
          {!customer.name.trim() && <p className="muted">Tip: fill the customer name for the PDF header.</p>}
        </Modal>
      )}

      <ToastStack toasts={toasts} dismiss={dismiss} />
    </>
  );
}
