/**
 * Guest personalization — shared types.
 *
 * Design notes:
 *  - Anonymous guests keep a VERSIONED preference record in localStorage
 *    (see preferenceStore.ts). Authenticated sessions could in future store
 *    preferences server-side, but the current backend exposes no profile/
 *    preferences endpoint — none is invented here (see
 *    docs/verification/GUEST_PERSONALIZATION.md).
 *  - Every option id below must exist in preferenceOptions.ts so the modal,
 *    the matcher and the tests share one vocabulary.
 */

export type SpiceLevel = 'mild' | 'medium' | 'hot';

export interface GuestPreferences {
  /** Diet styles the guest eats: 'vegetarian' | 'non-vegetarian' | 'vegan' */
  dietStyles: string[];
  /** Cuisine ids: 'north-indian' | 'south-indian' | 'indo-chinese' | 'italian' | 'street-food' */
  cuisines: string[];
  /** Favourite menu categories — REAL backend category ids (see menuSnapshot.ts). */
  favoriteCategories: number[];
  /** Spice tolerance: 'mild' | 'medium' | 'hot' */
  spiceLevels: SpiceLevel[];
  /** 'sweet' | 'savory' */
  tasteProfile: string[];
  /** Beverage kinds: 'coffee' | 'tea' | 'cold-drinks' | 'juices' | 'lassi' */
  beverages: string[];
  /** Ingredient exclusions: 'dairy' | 'gluten' | 'nuts' | 'eggs' */
  exclusions: string[];
  /** Budget bands derived from the real price range (₹59–₹199): 'under-100' | '100-150' | '150-plus' */
  budget: string[];
  /** 'everyday' | 'family' | 'celebration' | 'quick-snack' */
  occasions: string[];
  /** Optional free-text note (max 280 chars). May contain health info — see
   *  recommendations.ts for the strict no-medical-claims handling. */
  note: string;
}

export type GuestPrefStatus = 'saved' | 'skipped';

export interface GuestPrefRecord {
  version: 1;
  /** 'saved' = guest saved preferences; 'skipped' = guest dismissed onboarding.
   *  Distinct states so the modal never loops on guests who skipped. */
  status: GuestPrefStatus;
  /** Present only for status === 'saved'. */
  preferences: GuestPreferences | null;
  updatedAt: string; // ISO 8601
}

export const EMPTY_PREFERENCES: GuestPreferences = {
  dietStyles: [],
  cuisines: [],
  favoriteCategories: [],
  spiceLevels: [],
  tasteProfile: [],
  beverages: [],
  exclusions: [],
  budget: [],
  occasions: [],
  note: '',
};

export const NOTE_MAX_LENGTH = 280;
