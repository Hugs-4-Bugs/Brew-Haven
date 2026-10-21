/**
 * Guest preference persistence — versioned localStorage record.
 *
 * Contract (deliberately defensive — this is the first guest-facing code that
 * runs on the homepage, it must NEVER crash the page):
 *  - Storage key is versioned (brewHaven.guestPrefs.v1). Unknown versions,
 *    corrupt JSON or a disabled localStorage all degrade gracefully to
 *    "no record" (first-visit state) without throwing.
 *  - 'saved' and 'skipped' are DISTINCT states: a guest who skipped (or
 *    dismissed) onboarding is never re-prompted until they explicitly ask.
 *  - All writes dispatch PREFS_EVENT on window so mounted pages can react
 *    without a full page reload.
 *  - The free-text note is sanitised on load (length-capped, string-coerced).
 */

import {
  EMPTY_PREFERENCES,
  NOTE_MAX_LENGTH,
  type GuestPreferences,
  type GuestPrefRecord,
} from './types';

export const PREFS_STORAGE_KEY = 'brewHaven.guestPrefs.v1';
export const PREFS_EVENT = 'brewhaven:guest-prefs';

/** Coerces anything into a safe GuestPreferences shape (unknown keys dropped). */
export function sanitizePreferences(input: unknown): GuestPreferences {
  const src = (input && typeof input === 'object' ? input : {}) as Record<string, unknown>;
  const strArr = (v: unknown): string[] =>
    Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
  const numArr = (v: unknown): number[] =>
    Array.isArray(v) ? v.filter((x): x is number => typeof x === 'number' && Number.isFinite(x)) : [];
  const note = typeof src.note === 'string' ? src.note.slice(0, NOTE_MAX_LENGTH) : '';
  return {
    dietStyles: strArr(src.dietStyles),
    cuisines: strArr(src.cuisines),
    favoriteCategories: numArr(src.favoriteCategories),
    spiceLevels: strArr(src.spiceLevels).filter((s): s is 'mild' | 'medium' | 'hot' =>
      s === 'mild' || s === 'medium' || s === 'hot',
    ),
    tasteProfile: strArr(src.tasteProfile),
    beverages: strArr(src.beverages),
    exclusions: strArr(src.exclusions),
    budget: strArr(src.budget),
    occasions: strArr(src.occasions),
    note,
  };
}

/** Loads the stored record; null when absent, corrupt or of an unknown version. */
export function loadGuestPrefRecord(): GuestPrefRecord | null {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(PREFS_STORAGE_KEY);
  } catch {
    return null; // storage unavailable (private mode / disabled)
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    if (!parsed || typeof parsed !== 'object') return null;
    if (parsed.version !== 1) return null; // future schema — treat as fresh visit
    if (parsed.status !== 'saved' && parsed.status !== 'skipped') return null;
    const record: GuestPrefRecord = {
      version: 1,
      status: parsed.status,
      preferences:
        parsed.status === 'saved'
          ? sanitizePreferences(parsed.preferences)
          : null,
      updatedAt: typeof parsed.updatedAt === 'string' ? parsed.updatedAt : new Date().toISOString(),
    };
    // A 'saved' record whose preferences sanitize down to completely empty is
    // treated as not-saved (guest gets another chance to pick something).
    if (record.status === 'saved') {
      const p = record.preferences as GuestPreferences;
      const hasAnything =
        p.dietStyles.length || p.cuisines.length || p.favoriteCategories.length ||
        p.spiceLevels.length || p.tasteProfile.length || p.beverages.length ||
        p.exclusions.length || p.budget.length || p.occasions.length || p.note.trim().length;
      if (!hasAnything) return null;
    }
    return record;
  } catch {
    return null; // corrupt JSON — graceful first-visit fallback
  }
}

function persist(record: GuestPrefRecord): boolean {
  try {
    localStorage.setItem(PREFS_STORAGE_KEY, JSON.stringify(record));
  } catch {
    return false; // quota / disabled — in-session state still works
  }
  notify();
  return true;
}

function notify(): void {
  try {
    window.dispatchEvent(new CustomEvent(PREFS_EVENT, { detail: recordSnapshot() }));
  } catch {
    /* non-DOM environment (tests) */
  }
}

function recordSnapshot(): GuestPrefRecord | null {
  return loadGuestPrefRecord();
}

/** Persists a guest's saved preferences. Returns false when storage failed. */
export function savePreferences(prefs: GuestPreferences): boolean {
  return persist({
    version: 1,
    status: 'saved',
    preferences: sanitizePreferences(prefs),
    updatedAt: new Date().toISOString(),
  });
}

/** Marks onboarding as dismissed (skip / close). The guest is never re-prompted. */
export function skipOnboarding(): boolean {
  return persist({ version: 1, status: 'skipped', preferences: null, updatedAt: new Date().toISOString() });
}

/** Clears the record entirely — next visit is treated as first-visit again. */
export function clearGuestPrefs(): boolean {
  try {
    localStorage.removeItem(PREFS_STORAGE_KEY);
  } catch {
    return false;
  }
  notify();
  return true;
}

export { EMPTY_PREFERENCES };
