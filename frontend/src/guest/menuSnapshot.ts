/**
 * Guest menu data source.
 *
 * BACKEND CONTRACT (verified against the live Spring Boot backend):
 *  - GET /product/productList and /category/get require a valid JWT
 *    (SecurityConfig: anyRequest().authenticated()). Anonymous guests get 403.
 *  - The security posture is deliberately NOT changed for personalization
 *    (see task boundary: no backend/auth changes without approval).
 *
 * Data strategy (documented in docs/verification/GUEST_PERSONALIZATION.md):
 *  1. SNAPSHOT (default for anonymous guests): a dated snapshot of the REAL
 *     published menu captured from the live MariaDB CafeMgt database on
 *     2026-10-21. Every name, price, category and description below is real
 *     data — nothing is invented. Test/evidence artifacts that exist in the
 *     database (Probe Latte v2, E2E Special Latte, E2E Brownie, NoDesc Cookie)
 *     are excluded from the curated guest snapshot but DO appear when live
 *     data is available, because they are real backend rows.
 *  2. LIVE (authenticated sessions): fetched through the existing API layer
 *     (api/cafe.ts). On any failure the page falls back to the snapshot.
 *
 * Ingredient tags: `verified: true` ONLY when the ingredient is evident from
 * the item's real name/description (e.g. "Butter Naan with garlic" → dairy).
 * Wheat/gluten is never marked verified (no description states it) — those
 * tags are advisory and always accompanied by a "confirm with staff" note.
 * No allergen claims are invented.
 */

import { getCategories, getProducts } from '../api/cafe';
import { getToken } from '../api/client';

export type ItemTagKind =
  | 'beverage' | 'coffee' | 'dessert' | 'snack' | 'main-course'
  | 'spicy' | 'sweet' | 'high-protein'
  | 'dairy' | 'gluten' | 'nuts' | 'eggs';

export interface ItemTag {
  kind: ItemTagKind;
  /** true = evident from the real item name/description; false = advisory only. */
  verified: boolean;
}

export interface GuestMenuItem {
  id: number;
  name: string;
  description: string;
  price: number;
  categoryId: number;
  categoryName: string;
  /** Backend availability contract: 'true' = available. */
  status: 'true' | 'false';
  tags: ItemTag[];
  /** Presentation metadata (frontend-curated, not backend data). */
  visual: { emoji: string; hue: number };
}

export interface MenuSource {
  items: GuestMenuItem[];
  source: 'live' | 'snapshot';
  /** Snapshot capture date (ISO) — only for source === 'snapshot'. */
  capturedAt?: string;
}

export const SNAPSHOT_CAPTURED_AT = '2026-10-21';

// ---------------------------------------------------------------- category metadata

interface CategoryMeta {
  emoji: string;
  hue: number;
  cuisine: string;
  kind: 'main-course' | 'snack' | 'beverage' | 'dessert';
}

const CATEGORY_META: Record<number, CategoryMeta> = {
  1: { emoji: '🍕', hue: 12, cuisine: 'italian', kind: 'main-course' },      // Pizza
  2: { emoji: '🫓', hue: 32, cuisine: 'south-indian', kind: 'main-course' }, // Dosa
  3: { emoji: '🥟', hue: 40, cuisine: 'street-food', kind: 'snack' },        // Samosa
  4: { emoji: '🍛', hue: 22, cuisine: 'north-indian', kind: 'main-course' }, // Litti-chokha
  5: { emoji: '🫓', hue: 38, cuisine: 'north-indian', kind: 'main-course' }, // Butter-nan
  6: { emoji: '🍚', hue: 48, cuisine: 'indo-chinese', kind: 'main-course' }, // Fried-Rice
  7: { emoji: '🥘', hue: 18, cuisine: 'north-indian', kind: 'main-course' }, // Roti sabji
  8: { emoji: '🧋', hue: 26, cuisine: '', kind: 'beverage' },                // Cold Coffee
  9: { emoji: '🥤', hue: 42, cuisine: '', kind: 'beverage' },                // Lassi
  10: { emoji: '🍪', hue: 350, cuisine: '', kind: 'dessert' },               // E2E Sweets
};

const FALLBACK_META: CategoryMeta = { emoji: '🍽️', hue: 210, cuisine: '', kind: 'main-course' };

/** Cuisines actually represented on the real menu (used by preference options). */
export const MENU_CUISINES = ['north-indian', 'south-indian', 'indo-chinese', 'italian', 'street-food'];

// ---------------------------------------------------------------- tag derivation

const DAIRY_RE = /butter|paneer|lassi|ice cream|milk|cheese|cream|yogurt|curd/gi;
const SWEET_RE = /\bsweet\b/gi;
const SPICY_RE = /\bspicy\b|\bchilli\b|\bchili\b/gi;
const COFFEE_RE = /coffee/gi;
const NUTS_RE = /\b(nut|almond|cashew|pistachio|walnut|peanut)s?\b/gi;
const EGGS_RE = /\beggs?\b/gi;
const WHEAT_HINTS_RE = /naan|roti|pizza|samosa|litti|cookie|brownie|bread|paratha|puri/gi;

/**
 * Derives ingredient/attribute tags from the REAL item text.
 * `verified` is only true when the text itself is evidence.
 */
export function deriveTags(name: string, description: string, categoryId: number): ItemTag[] {
  const text = `${name} ${description ?? ''}`;
  const meta = CATEGORY_META[categoryId] ?? FALLBACK_META;
  const tags: ItemTag[] = [];
  const has = (re: RegExp) => re.test(text);

  // Attribute tags from the item's real category kind.
  tags.push({ kind: meta.kind, verified: true }); // category assignment is real backend data
  if (meta.kind === 'beverage' && COFFEE_RE.test(name)) tags.push({ kind: 'coffee', verified: true });

  // Ingredient/attribute evidence from name + description text.
  if (has(DAIRY_RE)) tags.push({ kind: 'dairy', verified: true });
  if (has(SWEET_RE)) tags.push({ kind: 'sweet', verified: true });
  if (has(SPICY_RE)) tags.push({ kind: 'spicy', verified: true });
  if (/\bpaneer\b/i.test(text)) tags.push({ kind: 'high-protein', verified: true });
  if (has(NUTS_RE)) tags.push({ kind: 'nuts', verified: true });
  if (has(EGGS_RE)) tags.push({ kind: 'eggs', verified: true });

  // Advisory (UNVERIFIED) gluten hint — no menu description states wheat content.
  if (WHEAT_HINTS_RE.test(name)) tags.push({ kind: 'gluten', verified: false });

  return tags;
}

function makeItem(
  id: number, name: string, description: string, price: number,
  categoryId: number, categoryName: string, status: 'true' | 'false',
): GuestMenuItem {
  return {
    id, name, description, price, categoryId, categoryName, status,
    tags: deriveTags(name, description, categoryId),
    visual: { emoji: (CATEGORY_META[categoryId] ?? FALLBACK_META).emoji, hue: (CATEGORY_META[categoryId] ?? FALLBACK_META).hue },
  };
}

// ---------------------------------------------------------------- snapshot (REAL menu data)

/**
 * REAL menu snapshot captured 2026-10-21 from MariaDB CafeMgt
 * (SELECT id, name, category_fk, price, status, description FROM product).
 * Names, prices, categories and descriptions are verbatim database values.
 */
export const MENU_SNAPSHOT: MenuSource = {
  source: 'snapshot',
  capturedAt: SNAPSHOT_CAPTURED_AT,
  items: [
    makeItem(1, 'Veggie Pizza', 'Veggie Pizza with olives', 199, 1, 'Pizza', 'true'),
    makeItem(2, 'Masala Dosa', 'South Indian Masala Dosa with chutney', 139, 2, 'Dosa', 'true'),
    makeItem(3, 'Paneer Samosa', 'Spicy Paneer Samosa with chutney', 79, 3, 'Samosa', 'true'),
    makeItem(4, 'Litti Chokha', 'Litti-Chokha with roasted baingan', 149, 4, 'Litti-chokha', 'true'),
    makeItem(5, 'Butter Naan', 'Butter Naan with garlic', 89, 5, 'Butter-nan', 'true'),
    makeItem(6, 'Veg Fried Rice', 'Veggie Fried Rice with mixed veggies', 129, 6, 'Fried-Rice', 'true'),
    makeItem(7, 'Tandoori Roti', 'Tandoori Roti with butter', 79, 7, 'Roti sabji', 'true'),
    makeItem(8, 'Cold Coffee', 'Cold Coffee with ice cream', 159, 8, 'Cold Coffee', 'true'),
    makeItem(9, 'Aloo Tikki', 'Spicy Aloo Tikki with chutney', 89, 2, 'Dosa', 'true'),
    makeItem(10, 'Mango Lassi', 'Sweet Lassi with mango flavor', 69, 9, 'Lassi', 'true'),
  ],
};

// ---------------------------------------------------------------- live resolution

/** True when a menu item is available (backend status contract: 'true'). */
export function isAvailable(item: GuestMenuItem): boolean {
  return item.status === 'true';
}

/**
 * Resolves the guest menu: LIVE data when an authenticated session exists
 * (backend requires JWT), otherwise the dated real-menu snapshot.
 * Never throws — falls back to the snapshot on any error.
 */
export async function resolveGuestMenu(): Promise<MenuSource> {
  if (!getToken()) return MENU_SNAPSHOT;
  try {
    const [products, categories] = await Promise.all([getProducts(), getCategories()]);
    const catName = new Map<number, string>();
    for (const c of categories) catName.set(c.id, c.name);
    const items = products.map((p): GuestMenuItem => {
      const catId = p.categoryId ?? 0;
      return makeItem(
        p.id,
        p.name ?? 'Unnamed item',
        p.description ?? '',
        p.price ?? 0,
        catId,
        catName.get(catId) ?? 'Menu',
        p.status === 'false' ? 'false' : 'true',
      );
    });
    if (!items.length) return MENU_SNAPSHOT; // defensive: empty live menu → snapshot
    return { items, source: 'live' };
  } catch {
    return MENU_SNAPSHOT; // live fetch failed (expired token, backend down) → snapshot
  }
}
