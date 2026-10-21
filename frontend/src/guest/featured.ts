/**
 * Featured / signature / popular item configuration.
 *
 * The backend has NO signature or featured-item flag (no such API exists —
 * none is invented). Until restaurant-controlled featured management is
 * approved and built server-side, signature status lives in this documented
 * frontend configuration. Item NAMES, PRICES, DESCRIPTIONS and AVAILABILITY
 * are always resolved from the real menu data at runtime — only the
 * "featured" designation and a short tagline live here.
 *
 * Smallest backend change that would enable restaurant-controlled featured
 * items (proposed separately for approval): a nullable `featured` boolean on
 * Product + an admin-guarded update endpoint + inclusion in the product API
 * payloads.
 */

import { isAvailable, type GuestMenuItem } from './menuSnapshot';

/** Signature dish ids (real backend product ids, curated by the restaurant). */
export const SIGNATURE_ITEM_IDS = [1, 2, 4, 8, 10];

/** Restaurant-selected favourites (NOT live sales data — labelled as such). */
export const POPULAR_ITEM_IDS = [2, 8, 10, 1];

/** Short curated taglines for the signature carousel. */
const SIGNATURE_TAGLINES: Record<number, string> = {
  1: 'Our pizzeria-style signature, loaded with olives.',
  2: 'The South Indian classic, done right.',
  4: 'A slow-roasted Bihari special.',
  8: 'Chilled, creamy and blended fresh.',
  10: 'Sweet, cold and unmistakably mango.',
};

export interface FeaturedItem {
  item: GuestMenuItem;
  tagline: string;
}

/**
 * Resolves configured featured ids against the real menu.
 * Missing ids and UNAVAILABLE items are skipped — the carousel never
 * features an item known to be unavailable.
 */
export function resolveFeatured(items: GuestMenuItem[], ids: number[]): FeaturedItem[] {
  const byId = new Map(items.map((i) => [i.id, i]));
  const out: FeaturedItem[] = [];
  for (const id of ids) {
    const item = byId.get(id);
    if (!item || !isAvailable(item)) continue;
    out.push({ item, tagline: SIGNATURE_TAGLINES[id] ?? `A ${item.categoryName} favourite.` });
  }
  return out;
}
