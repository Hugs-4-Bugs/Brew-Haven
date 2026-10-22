/**
 * Guest cart — localStorage-backed, guest-and-customer shared.
 *
 * The cart powers the customer ORDER journey: guests browse and add items
 * anonymously; placing the order requires signing in (the existing billing
 * API is JWT-protected), which is the honest, zero-new-security-surface way
 * to give customers a real checkout.
 *
 * Funnel measurement (CART_CREATED / CHECKOUT_STARTED / ORDER_COMPLETED) is
 * fire-and-forget for signed-in sessions and silently skipped for anonymous
 * ones — see api/revenue.recordEvent.
 */

import { recordEvent } from '../api/revenue';
import type { GuestMenuItem } from './menuSnapshot';

export interface CartLine {
  id: number;
  name: string;
  price: number;
  quantity: number;
  category?: string;
}

const CART_KEY = 'brewHaven.cart.v1';
export const CART_EVENT = 'brewhaven:cart';

function readCart(): CartLine[] {
  try {
    const raw = localStorage.getItem(CART_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (l): l is CartLine =>
        l && typeof l.id === 'number' && typeof l.name === 'string' &&
        typeof l.price === 'number' && typeof l.quantity === 'number' && l.quantity > 0,
    );
  } catch {
    return [];
  }
}

function writeCart(lines: CartLine[]): void {
  try {
    localStorage.setItem(CART_KEY, JSON.stringify(lines));
  } catch {
    /* storage unavailable — cart lives only in memory via the event */
  }
  window.dispatchEvent(new CustomEvent(CART_EVENT, { detail: lines }));
}

export function getCart(): CartLine[] {
  return readCart();
}

export function addToCart(item: GuestMenuItem, quantity = 1, viaUpsell = false): void {
  const lines = readCart();
  const existing = lines.find((l) => l.id === item.id);
  if (existing) existing.quantity += quantity;
  else {
    lines.push({ id: item.id, name: item.name, price: item.price, quantity, category: item.categoryName });
    if (lines.length === 1) void recordEvent('CART_CREATED');
  }
  writeCart(lines);
  if (viaUpsell) void recordEvent('UPSELL_ACCEPTED', item.id, 'pairing accepted into cart');
}

export function setQuantity(itemId: number, quantity: number): void {
  let lines = readCart();
  if (quantity <= 0) lines = lines.filter((l) => l.id !== itemId);
  else {
    const line = lines.find((l) => l.id === itemId);
    if (line) line.quantity = quantity;
  }
  writeCart(lines);
}

export function removeFromCart(itemId: number): void {
  writeCart(readCart().filter((l) => l.id !== itemId));
}

export function clearCart(): void {
  writeCart([]);
}

export function cartTotal(lines: CartLine[]): number {
  return lines.reduce((sum, l) => sum + l.price * l.quantity, 0);
}

export function cartCount(lines: CartLine[]): number {
  return lines.reduce((sum, l) => sum + l.quantity, 0);
}

/** Serialises the cart to the backend's productDetails JSON contract. */
export function toProductDetails(lines: CartLine[]): string {
  return JSON.stringify(
    lines.map((l) => ({
      id: l.id,
      name: l.name,
      category: l.category ?? '',
      quantity: l.quantity,
      price: l.price,
      total: l.price * l.quantity,
    })),
  );
}
