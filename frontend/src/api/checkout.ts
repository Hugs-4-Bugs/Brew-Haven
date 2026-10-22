/**
 * Checkout API layer — the SERVER-PRICED payment flow.
 *
 * Money integrity contract:
 *  - Every amount displayed to the user comes from POST /checkout/price and
 *    POST /checkout/intent (the backend pricing engine). Nothing here sends a
 *    price, discount or total to the backend — only {productId, quantity}.
 *  - Payment intents are created with a client idempotency key, so retries,
 *    double-clicks and refreshes reuse the same server-side intent.
 *  - A payment is only "successful" after the backend verified it (provider
 *    signature / webhook / operator attestation) — never from client state.
 */

import { request } from './client';

export interface PriceItem {
  productId: number;
  quantity: number;
}

export interface PriceBreakdown {
  subtotal: number;
  discount: number;
  tax: number;
  total: number;
  totalPaise: number;
  currency: string;
  couponApplied: boolean;
  couponCode?: string | null;
  couponName?: string | null;
  couponOfferType?: string | null;
  couponOfferValue?: number | null;
  couponRejectionReason?: string | null;
  items: {
    productId: number;
    name: string;
    category: string;
    unitPrice: number;
    quantity: number;
    lineTotal: number;
  }[];
}

export type IntentStatus = 'CREATED' | 'PENDING' | 'SUCCESS' | 'FAILED' | 'CANCELLED' | 'EXPIRED';
export type PayMethod = 'UPI' | 'CARD' | 'CASH';

export interface CheckoutIntent {
  reference: string;
  status: IntentStatus;
  method: PayMethod;
  provider: string;
  providerOrderId?: string | null;
  subtotal: number;
  discount: number;
  tax: number;
  total: number;
  amountPaise: number;
  currency: string;
  couponCode?: string | null;
  expiresAt?: number;
  checkout: {
    mode: 'SANDBOX' | 'RAZORPAY';
    sandbox: boolean;
    keyId?: string;
    providerOrderId?: string;
    amountPaise: number;
    currency: string;
  };
}

export interface IntentStatusResponse {
  reference: string;
  status: IntentStatus;
  method: PayMethod;
  total: number;
  amountPaise: number;
  providerPaymentId?: string | null;
  billUuid?: string | null;
}

export interface CheckoutReceipt {
  reference: string;
  status: string;
  alreadyCompleted?: boolean;
  billUuid?: string | null;
  transactionId?: string | null;
  amount: number;
  amountPaise?: number;
  method: string;
  provider?: string;
  couponCode?: string | null;
  subtotal?: number;
  discount?: number;
  tax?: number;
}

/** POST /checkout/price — authoritative pricing + coupon validation (the APPLY button). */
export async function priceCart(
  items: PriceItem[],
  couponCode?: string,
  email?: string,
): Promise<PriceBreakdown> {
  return request<PriceBreakdown>('/checkout/price', {
    method: 'POST',
    body: { items, couponCode: couponCode || undefined, email: email || undefined },
  });
}

/** POST /checkout/intent — idempotent payment-intent creation (server-priced). */
export async function createPaymentIntent(payload: {
  items: PriceItem[];
  method: PayMethod;
  clientKey: string;
  name: string;
  contactNumber: string;
  email: string;
  couponCode?: string;
  upiId?: string;
}): Promise<CheckoutIntent> {
  return request<CheckoutIntent>('/checkout/intent', { method: 'POST', body: payload });
}

/** GET /checkout/intent/{reference} — status polling for the waiting screen. */
export async function getIntent(reference: string): Promise<IntentStatusResponse> {
  return request<IntentStatusResponse>(`/checkout/intent/${encodeURIComponent(reference)}`);
}

/** GET /checkout/qr/{reference} — merchant UPI QR for the SERVER-COMPUTED amount. */
export async function getIntentQr(reference: string): Promise<string> {
  return request<string>(`/checkout/qr/${encodeURIComponent(reference)}`);
}

/** POST /checkout/confirm — provider-signature-verified or assisted confirmation. */
export async function confirmCheckout(payload: {
  reference: string;
  providerOrderId?: string;
  providerPaymentId?: string;
  signature?: string;
  assisted?: boolean;
}): Promise<CheckoutReceipt> {
  return request<CheckoutReceipt>('/checkout/confirm', { method: 'POST', body: payload });
}

/** POST /checkout/sandbox/settle — TEST settlement (403 when a real provider is active). */
export async function sandboxSettle(
  reference: string,
  outcome: 'SUCCESS' | 'FAILED',
): Promise<CheckoutReceipt> {
  return request<CheckoutReceipt>('/checkout/sandbox/settle', {
    method: 'POST',
    body: { reference, outcome },
  });
}

/** POST /checkout/cancel — user closed checkout. */
export async function cancelIntent(reference: string): Promise<string> {
  return request<string>('/checkout/cancel', { method: 'POST', body: { reference } });
}
