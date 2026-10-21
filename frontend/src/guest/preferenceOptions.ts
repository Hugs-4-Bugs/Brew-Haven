/**
 * Preference option definitions — mapped to the REAL menu.
 *
 * Rules (from the task contract):
 *  - Every option group supports MULTIPLE simultaneous selections (checkboxes).
 *  - Generic options with NO matching items on the current menu are clearly
 *    labelled (menuNote) instead of silently promising something we cannot
 *    recommend (e.g. tea — no tea items exist on the current menu).
 *  - Category options reference REAL backend category ids so the matcher can
 *    score real products.
 */

import type { SpiceLevel } from './types';

export interface PrefOption {
  id: string;
  label: string;
  /** Honest note shown when the current menu has no matching items. */
  menuNote?: string;
}

export interface PrefGroup {
  id: keyof import('./types').GuestPreferences;
  title: string;
  hint: string;
  options: PrefOption[];
}

export interface PrefStep {
  id: string;
  title: string;
  subtitle: string;
  groups: PrefGroup[];
}

// Real category ids from the live database (see menuSnapshot.ts).
const REAL_CATEGORIES: { id: number; label: string }[] = [
  { id: 1, label: 'Pizza' },
  { id: 2, label: 'Dosa' },
  { id: 3, label: 'Samosa' },
  { id: 4, label: 'Litti-chokha' },
  { id: 5, label: 'Butter-nan' },
  { id: 6, label: 'Fried-Rice' },
  { id: 7, label: 'Roti sabji' },
  { id: 8, label: 'Cold Coffee' },
  { id: 9, label: 'Lassi' },
  { id: 10, label: 'Sweets' },
];

export const PREFERENCE_STEPS: PrefStep[] = [
  {
    id: 'food',
    title: 'What do you love to eat?',
    subtitle: 'Pick as many as you like — this shapes your personal picks.',
    groups: [
      {
        id: 'dietStyles',
        title: 'Food style',
        hint: 'Multiple choices welcome.',
        options: [
          { id: 'vegetarian', label: 'Vegetarian' },
          { id: 'non-vegetarian', label: 'Non-vegetarian', menuNote: 'No non-veg items on the current menu' },
          { id: 'vegan', label: 'Vegan', menuNote: 'Vegan suitability not verified — please confirm with staff' },
        ],
      },
      {
        id: 'cuisines',
        title: 'Favourite cuisines',
        hint: 'Cuisines actually offered on our menu.',
        options: [
          { id: 'north-indian', label: 'North Indian' },
          { id: 'south-indian', label: 'South Indian' },
          { id: 'indo-chinese', label: 'Indo-Chinese' },
          { id: 'italian', label: 'Italian (pizza)' },
          { id: 'street-food', label: 'Street food / snacks' },
        ],
      },
      {
        id: 'favoriteCategories',
        title: 'Favourite menu sections',
        hint: 'Straight from our real menu categories.',
        options: REAL_CATEGORIES.map((c) => ({ id: String(c.id), label: c.label })),
      },
    ],
  },
  {
    id: 'taste',
    title: 'How do you like it?',
    subtitle: 'Spice, sweetness and what you like to sip.',
    groups: [
      {
        id: 'spiceLevels',
        title: 'Spice tolerance',
        hint: 'Mild, medium or hot — your call.',
        options: [
          { id: 'mild', label: 'Mild' },
          { id: 'medium', label: 'Medium' },
          { id: 'hot', label: 'Hot' },
        ],
      },
      {
        id: 'tasteProfile',
        title: 'Sweet or savoury?',
        hint: '',
        options: [
          { id: 'sweet', label: 'Sweet tooth' },
          { id: 'savory', label: 'Savoury' },
        ],
      },
      {
        id: 'beverages',
        title: 'Beverages',
        hint: 'What we actually pour today.',
        options: [
          { id: 'coffee', label: 'Coffee' },
          { id: 'lassi', label: 'Lassi' },
          { id: 'cold-drinks', label: 'Cold beverages' },
          { id: 'tea', label: 'Tea', menuNote: 'No tea items on the current menu' },
          { id: 'juices', label: 'Fresh juices', menuNote: 'No juice items on the current menu' },
        ],
      },
    ],
  },
  {
    id: 'diet',
    title: 'Dietary needs & budget',
    subtitle: 'We take exclusions seriously — with honest limits.',
    groups: [
      {
        id: 'exclusions',
        title: 'Exclude / avoid',
        hint: 'We exclude items whose description clearly contains these. Anything we cannot verify, we flag for you to confirm with staff.',
        options: [
          { id: 'dairy', label: 'Dairy' },
          { id: 'gluten', label: 'Gluten', menuNote: 'Gluten content is not verified for any item — staff will confirm' },
          { id: 'nuts', label: 'Nuts', menuNote: 'Nut content is not verified for any item — staff will confirm' },
          { id: 'eggs', label: 'Eggs', menuNote: 'No egg items identified on the current menu' },
        ],
      },
      {
        id: 'budget',
        title: 'Spending range',
        hint: 'Bands reflect our real prices (₹59–₹199).',
        options: [
          { id: 'under-100', label: 'Under ₹100' },
          { id: '100-150', label: '₹100 – ₹150' },
          { id: '150-plus', label: '₹150+' },
        ],
      },
      {
        id: 'occasions',
        title: 'Occasion',
        hint: 'Helps us pick between a quick bite and a full feast.',
        options: [
          { id: 'quick-snack', label: 'Quick snack / beverage' },
          { id: 'everyday', label: 'Everyday meal' },
          { id: 'family', label: 'Family dining' },
          { id: 'celebration', label: 'Celebration / special treat' },
        ],
      },
    ],
  },
];

export const SPICE_LEVELS: SpiceLevel[] = ['mild', 'medium', 'hot'];
