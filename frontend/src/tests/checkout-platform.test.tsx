import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../auth/AuthContext';
import CheckoutDrawer from '../components/guest/CheckoutDrawer';
import * as checkoutApi from '../api/checkout';
import type { PriceBreakdown } from '../api/checkout';
import type { CartLine } from '../guest/cartStore';

/**
 * Checkout platform tests — money-integrity and the coupon APPLY flow.
 *
 * Invariant: the browser NEVER sends a price, discount or total to the backend —
 * only {productId, quantity} pairs (+ the coupon code and idempotency key).
 * All displayed amounts must come from the server pricing response.
 */

const baseQuote: PriceBreakdown = {
  subtotal: 500, discount: 0, tax: 0, total: 500, totalPaise: 50000, currency: 'INR',
  couponApplied: false, couponCode: null, couponRejectionReason: null,
  items: [{ productId: 1, name: 'Cold Coffee', category: 'Beverages', unitPrice: 250, quantity: 2, lineTotal: 500 }],
};

const discountedQuote: PriceBreakdown = {
  ...baseQuote, discount: 50, total: 450, totalPaise: 45000,
  couponApplied: true, couponCode: 'BREW3', couponName: 'Test offer',
  couponOfferType: 'PERCENT', couponOfferValue: 10,
};

describe('checkout API — money integrity', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('priceCart sends items + couponCode and NEVER any amount fields', async () => {
    const calls: { url: string; body: Record<string, unknown> }[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const path = String(url).replace(/^https?:\/\/[^/]+/, '');
      const body = init?.body ? JSON.parse(String(init.body)) : {};
      calls.push({ url: path, body });
      return new Response(JSON.stringify(baseQuote), { status: 200 });
    }));

    await checkoutApi.priceCart([{ productId: 1, quantity: 2 }], 'BREW3', 'c@x.io');
    const call = calls.find((c) => c.url === '/checkout/price');
    expect(call).toBeTruthy();
    expect(call?.body).toMatchObject({ items: [{ productId: 1, quantity: 2 }], couponCode: 'BREW3' });
    // The request must NOT carry any client-side money figure.
    for (const forbidden of ['amount', 'total', 'totalAmount', 'price', 'discount', 'subtotal']) {
      expect(call?.body).not.toHaveProperty(forbidden);
    }
    expect(JSON.stringify((call?.body as Record<string, unknown>).items)).not.toMatch(/"price"|"unitPrice"/);
  });

  it('createPaymentIntent carries the idempotency clientKey and no amounts', async () => {
    const calls: { url: string; body: Record<string, unknown> }[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const path = String(url).replace(/^https?:\/\/[^/]+/, '');
      const body = init?.body ? JSON.parse(String(init.body)) : {};
      calls.push({ url: path, body });
      const intent = {
        reference: 'PAY-TEST1', status: 'PENDING', method: 'UPI', provider: 'SANDBOX',
        providerOrderId: 'sbx_order_PAY-TEST1', subtotal: 500, discount: 0, tax: 0,
        total: 500, amountPaise: 50000, currency: 'INR', couponCode: null,
        checkout: { mode: 'SANDBOX', sandbox: true, amountPaise: 50000, currency: 'INR' },
      };
      return new Response(JSON.stringify(intent), { status: 200 });
    }));

    await checkoutApi.createPaymentIntent({
      items: [{ productId: 1, quantity: 2 }],
      method: 'UPI',
      clientKey: 'session-1:UPI:-:1x2',
      name: 'C', contactNumber: '9', email: 'c@x.io',
    });
    const call = calls.find((c) => c.url === '/checkout/intent');
    expect(call?.body).toHaveProperty('clientKey', 'session-1:UPI:-:1x2');
    for (const forbidden of ['amount', 'total', 'totalAmount', 'price', 'discount', 'subtotal']) {
      expect(call?.body).not.toHaveProperty(forbidden);
    }
  });
});

describe('CheckoutDrawer — server pricing + coupon APPLY', () => {
  const lines: CartLine[] = [
    { id: 1, name: 'Cold Coffee', price: 250, quantity: 2, category: 'Beverages' },
  ];

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });


  function mountDrawer(appliedCampaign: { id: number; name: string; code: string; offerType: string; offerValue: number } | null = null): void {
    localStorage.setItem('cafe.token', 'fake.jwt.token');
    render(
      <MemoryRouter>
        <AuthProvider>
          <CheckoutDrawer
            open
            onClose={() => undefined}
            lines={lines}
            appliedCampaign={appliedCampaign}
            onClearCampaign={() => undefined}
          />
        </AuthProvider>
      </MemoryRouter>,
    );
  }


  /** Routes /checkout/price statefully: first call (no coupon) -> baseQuote, then discounted. */
  function mockFetchPricing(rejected = false) {
    const priceCalls: Record<string, unknown>[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const path = String(url).replace(/^https?:\/\/[^/]+/, '');
      if (path === '/checkout/price') {
        const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
        priceCalls.push(body);
        const wantsCoupon = typeof body.couponCode === 'string' && body.couponCode.length > 0;
        const response = wantsCoupon
          ? (rejected
            ? { ...baseQuote, couponRejectionReason: 'This coupon has expired.' }
            : discountedQuote)
          : baseQuote;
        return new Response(JSON.stringify(response), { status: 200 });
      }
      if (path === '/user/me') {
        return new Response(JSON.stringify({ name: 'C', email: 'c@x.io', contactNumber: '9', role: 'user' }), { status: 200 });
      }
      return new Response(JSON.stringify({ message: 'not found' }), { status: 404 });
    }));
    return priceCalls;
  }

  it('renders the SERVER breakdown (subtotal / payable) — never client math', async () => {
    mockFetchPricing();
    mountDrawer();
    await waitFor(() => expect(screen.getByText('Subtotal')).toBeTruthy());
    // Scope to the breakdown panel so the cart-line total (₹500) cannot be confused with it.
    const breakdown = screen.getByLabelText('Price breakdown');
    expect(within(breakdown).getAllByText('₹500')).toHaveLength(2); // subtotal row + payable row (both server-computed)
    expect(within(breakdown).getByText(/Payable/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Pay ₹500/ })).toBeTruthy();
  });

  it('APPLY sends the code to the backend and shows the updated payable', async () => {
    const priceCalls = mockFetchPricing();
    mountDrawer();
    await waitFor(() => expect(screen.getByText('Subtotal')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/offer code/i), { target: { value: 'BREW3' } });
    fireEvent.click(screen.getByRole('button', { name: /^APPLY$/i }));

    await waitFor(() => expect(screen.getByText(/BREW3 applied/)).toBeTruthy());
    const appliedCall = priceCalls.find((c) => typeof c.couponCode === 'string');
    expect(appliedCall).toMatchObject({ couponCode: 'BREW3' });
    expect(appliedCall).not.toHaveProperty('discount'); // client never sends discounts

    // Payable updated from the SERVER response (500 -> 450), discount row visible.
    expect(screen.getByText('−₹50')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Pay ₹450/ })).toBeTruthy();
  });

  it('invalid coupon shows the server rejection reason and keeps the payable unchanged', async () => {
    mockFetchPricing(true);
    mountDrawer();
    await waitFor(() => expect(screen.getByText('Subtotal')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/offer code/i), { target: { value: 'BREW3' } });
    fireEvent.click(screen.getByRole('button', { name: /^APPLY$/i }));

    await waitFor(() => expect(screen.getByText(/This coupon has expired/i)).toBeTruthy());
    // No discount row, payable stays the server total without coupon.
    expect(screen.queryByText('−₹50')).toBeNull();
    expect(screen.getByRole('button', { name: /Pay ₹500/ })).toBeTruthy();
  });

  it('auto-applies a banner campaign code once on open', async () => {
    const priceCalls = mockFetchPricing();
    mountDrawer({ id: 3, name: 'Festive', code: 'BREW3', offerType: 'PERCENT', offerValue: 10 });
    await waitFor(() => expect(screen.getByText(/BREW3 applied/)).toBeTruthy());
    expect(priceCalls.some((c) => c.couponCode === 'BREW3')).toBe(true);
  });
});
