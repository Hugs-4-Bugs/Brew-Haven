/**
 * Smart complementary suggestions (rule-based, informational only).
 *
 * Rules are derived from the real menu structure: mains and snacks pair with
 * our beverages; desserts pair with coffee. Suggestions are NEVER added to
 * any cart automatically — the POS cart lives in the authenticated billing
 * flow and is untouched by the guest experience.
 */

import { isAvailable, type GuestMenuItem } from './menuSnapshot';

export interface PairingSuggestion {
  item: GuestMenuItem;
  why: string;
}

const BEVERAGE_CATS = new Set([8, 9]); // Cold Coffee, Lassi (real category ids)

/** Suggests up to two complementary items for a given menu item. */
export function pairingsFor(item: GuestMenuItem, allItems: GuestMenuItem[]): PairingSuggestion[] {
  const out: PairingSuggestion[] = [];
  const isMeal = item.tags.some((t) => t.kind === 'main-course' || t.kind === 'snack');
  const isDessert = item.tags.some((t) => t.kind === 'dessert');

  if (isMeal) {
    const coffee = allItems.find((i) => isAvailable(i) && i.tags.some((t) => t.kind === 'coffee') && i.id !== item.id);
    if (coffee) out.push({ item: coffee, why: 'A chilled coffee alongside makes it a combo.' });
    const lassi = allItems.find((i) => isAvailable(i) && i.categoryId === 9 && i.id !== item.id && (coffee?.id !== i.id));
    if (lassi) out.push({ item: lassi, why: 'A cool lassi balances the spices.' });
  } else if (isDessert) {
    const coffee = allItems.find((i) => isAvailable(i) && i.tags.some((t) => t.kind === 'coffee'));
    if (coffee) out.push({ item: coffee, why: 'Coffee and dessert are a classic pair.' });
  } else if (BEVERAGE_CATS.has(item.categoryId)) {
    const snack = allItems.find((i) => isAvailable(i) && i.tags.some((t) => t.kind === 'snack') && i.id !== item.id);
    if (snack) out.push({ item: snack, why: 'A little something to munch alongside.' });
  }
  return out.slice(0, 2);
}
