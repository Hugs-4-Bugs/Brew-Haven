import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  addToCart, cartCount, cartTotal, clearCart, getCart, removeFromCart,
  setQuantity, toProductDetails, CART_EVENT, type CartLine,
} from '../guest/cartStore';
import type { GuestMenuItem } from '../guest/menuSnapshot';

// recordEvent fires fetch calls — stub them out completely (fire-and-forget).
vi.mock('../api/revenue', () => ({
  recordEvent: vi.fn().mockResolvedValue(undefined),
}));

function item(id: number, price: number, name = `Item ${id}`): GuestMenuItem {
  return {
    id, name, description: '', price, categoryId: 1, categoryName: 'Pizza',
    status: 'true', tags: [], visual: { emoji: '🍕', hue: 30 },
  };
}

describe('cartStore — guest cart', () => {
  beforeEach(() => {
    localStorage.clear();
  });
  afterEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  it('adds items and merges quantities for the same product', () => {
    addToCart(item(1, 199));
    addToCart(item(1, 199));
    addToCart(item(2, 99));
    const cart = getCart();
    expect(cart).toHaveLength(2);
    expect(cart.find((l) => l.id === 1)?.quantity).toBe(2);
    expect(cartCount(cart)).toBe(3);
  });

  it('computes totals with per-line quantity pricing', () => {
    addToCart(item(1, 199));
    addToCart(item(1, 199));
    addToCart(item(2, 99));
    expect(cartTotal(getCart())).toBe(199 * 2 + 99);
  });

  it('setQuantity removes at zero and removeFromCart filters by id', () => {
    addToCart(item(1, 50));
    addToCart(item(2, 60));
    setQuantity(1, 0);
    expect(getCart().map((l) => l.id)).toEqual([2]);
    removeFromCart(2);
    expect(getCart()).toHaveLength(0);
  });

  it('serialises to the backend productDetails contract', () => {
    addToCart(item(3, 120));
    addToCart(item(3, 120));
    const parsed = JSON.parse(toProductDetails(getCart())) as CartLine[];
    expect(parsed[0]).toMatchObject({ id: 3, quantity: 2, price: 120, total: 240 });
  });

  it('survives corrupt storage (returns empty cart, never throws)', () => {
    localStorage.setItem('brewHaven.cart.v1', '{not json');
    expect(getCart()).toEqual([]);
  });

  it('dispatches a cart event on change', () => {
    const listener = vi.fn();
    window.addEventListener(CART_EVENT, listener);
    addToCart(item(9, 10));
    expect(listener).toHaveBeenCalled();
    window.removeEventListener(CART_EVENT, listener);
    clearCart();
  });
});
