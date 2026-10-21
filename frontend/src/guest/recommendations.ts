/**
 * Personalized recommendation engine.
 *
 * CORRECTNESS CONTRACTS (from the task):
 *  1. Grounded: every recommendation refers to an ACTUAL menu item (snapshot
 *     or live backend data). Nothing is invented — no fabricated dishes,
 *     prices, availability or health properties.
 *  2. Deterministic first: a transparent rule-based matcher scores real items
 *     against saved preferences and produces human explanations.
 *  3. Optional AI layer: when VITE_AI_RECOMMENDATION_URL is configured, the
 *     engine offers the REAL candidate items to that service and only accepts
 *     picks whose ids exist in the candidate set. Any failure/timeout (3s)
 *     falls back to the rule-based matcher. The guest's free-text NOTE is
 *     NEVER sent to the external service — only structured preference labels.
 *  4. Health safety: a note mentioning health concerns only shapes matching
 *     and produces a "confirm with staff / not medical advice" advisory.
 *     The engine NEVER claims a dish treats, cures or is medically safe.
 *  5. Unavailable items (backend status 'false') are excluded outright.
 */

import { isAvailable, type GuestMenuItem } from './menuSnapshot';
import { EMPTY_PREFERENCES, type GuestPreferences } from './types';

export interface Recommendation {
  item: GuestMenuItem;
  score: number;
  reasons: string[];
}

export type RecommendationSource = 'rules' | 'ai';

export interface RecommendationResult {
  recommendations: Recommendation[];
  advisories: string[];
  excludedCount: number;
  source: RecommendationSource;
  /** Set when the AI endpoint was configured but unusable. */
  aiNote?: string;
  /** Set when no positive matches existed (fallback suggestions used). */
  emptyReason?: string;
}

const CATEGORY_TO_CUISINE: Record<number, string> = {
  1: 'italian', 2: 'south-indian', 3: 'street-food', 4: 'north-indian',
  5: 'north-indian', 6: 'indo-chinese', 7: 'north-indian', 8: '', 9: '', 10: '',
};

const BUDGET_BANDS: Record<string, [number, number]> = {
  'under-100': [0, 99],
  '100-150': [100, 150],
  '150-plus': [151, Number.MAX_SAFE_INTEGER],
};

const HEALTH_TERMS = [
  'diabet', 'allerg', 'asthma', 'blood pressure', 'hypertens', 'digest', 'gas',
  'ulcer', 'pregnan', 'migraine', 'intoleran', 'celiac', 'coeliac', 'heart',
  'cholesterol', 'kidney', 'gastric', 'acidity',
];

const HEALTH_ADVISORY =
  'Thanks for sharing your dietary note. We use it only to shape suggestions — it is not medical advice. Please confirm ingredients with our staff before ordering.';

/** Detects health-related notes so a safety advisory can be shown (never a claim). */
export function noteMentionsHealth(note: string): boolean {
  const n = note.toLowerCase();
  return HEALTH_TERMS.some((t) => n.includes(t));
}

interface Scored {
  item: GuestMenuItem;
  score: number;
  reasons: Set<string>;
}

function hasTag(item: GuestMenuItem, kind: string, verifiedOnly = false): boolean {
  return item.tags.some((t) => t.kind === kind && (!verifiedOnly || t.verified));
}

/** Rule-based matcher — pure, deterministic, explainable. */
export function recommendItems(prefs: GuestPreferences, allItems: GuestMenuItem[]): RecommendationResult {
  const p = { ...EMPTY_PREFERENCES, ...prefs };
  const advisories: string[] = [];
  const noteLower = p.note.trim().toLowerCase();

  // ---- exclusions first (hard rules on VERIFIED evidence only)
  const wantsDairyExcluded =
    p.exclusions.includes('dairy') || /\b(dairy|milk|lactose)\b/.test(noteLower);
  let excludedCount = 0;

  const candidates: GuestMenuItem[] = [];
  for (const item of allItems) {
    if (!isAvailable(item)) continue; // unavailable items never recommended
    if (wantsDairyExcluded && hasTag(item, 'dairy', true)) {
      excludedCount += 1;
      continue;
    }
    candidates.push(item);
  }

  if (wantsDairyExcluded) {
    if (candidates.length === 0) {
      return {
        recommendations: [], advisories: [
          'Every current menu item lists a dairy ingredient in its description — please ask our staff about dairy-free preparation options.',
        ],
        excludedCount, source: 'rules',
      };
    }
    advisories.push(`${excludedCount} item${excludedCount === 1 ? '' : 's'} listing dairy in the description ${excludedCount === 1 ? 'was' : 'were'} excluded for you. Cross-contact cannot be ruled out — please confirm with staff.`);
  }
  if (p.exclusions.includes('gluten')) {
    advisories.push('We cannot verify gluten content from our menu data. Please confirm ingredients and preparation with our staff before ordering.');
  }
  if (p.exclusions.includes('nuts')) {
    advisories.push('Nut content is not verified for any menu item. Please confirm with our staff before ordering.');
  }
  if (noteMentionsHealth(p.note)) advisories.push(HEALTH_ADVISORY);

  // ---- honest diet-style advisories
  if (p.dietStyles.includes('non-vegetarian')) {
    advisories.push('No non-vegetarian items are on the current menu — showing our vegetarian picks instead.');
  }
  if (p.dietStyles.includes('vegan')) {
    advisories.push('Vegan suitability is not verified for any menu item — please confirm ingredients with our staff.');
  }

  // ---- score each candidate
  const scored: Scored[] = candidates.map((item) => {
    const score: number[] = [];
    const reasons = new Set<string>();

    // favourite category (REAL backend category ids)
    if (p.favoriteCategories.includes(item.categoryId)) {
      score.push(30);
      reasons.add(`from your favourite section — ${item.categoryName}`);
    }
    // cuisine
    const cuisine = CATEGORY_TO_CUISINE[item.categoryId] ?? '';
    if (cuisine && p.cuisines.includes(cuisine)) {
      score.push(15);
      reasons.add(`matches your ${cuisine.replace('-', ' ')} taste`);
    }
    // beverages
    if (p.beverages.includes('coffee') && hasTag(item, 'coffee')) {
      score.push(25);
      reasons.add('a coffee you asked for');
    }
    if (p.beverages.includes('lassi') && item.categoryId === 9) {
      score.push(25);
      reasons.add('a lassi you asked for');
    }
    if (p.beverages.includes('cold-drinks') && hasTag(item, 'beverage')) {
      score.push(15);
      reasons.add('a chilled beverage');
    }
    // spice
    const spicy = hasTag(item, 'spicy');
    if (p.spiceLevels.includes('hot') && spicy) {
      score.push(15);
      reasons.add('carries the heat you enjoy');
    } else if (p.spiceLevels.includes('medium') && spicy) {
      score.push(8);
    } else if (p.spiceLevels.length > 0 && p.spiceLevels.every((s) => s === 'mild') && spicy) {
      score.push(-20); // penalise, never hide
    }
    // taste profile
    if (p.tasteProfile.includes('sweet') && hasTag(item, 'sweet')) {
      score.push(15);
      reasons.add('hits your sweet tooth');
    }
    if (p.tasteProfile.includes('savory') && hasTag(item, 'sweet')) score.push(-5);
    // budget (real prices)
    const inAnySelectedBand = p.budget.some((band) => {
      const range = BUDGET_BANDS[band];
      return range && item.price >= range[0] && item.price <= range[1];
    });
    if (p.budget.length > 0) {
      if (inAnySelectedBand) {
        score.push(15);
        reasons.add('fits your budget');
      } else {
        score.push(-10);
      }
    }
    // occasion
    if (p.occasions.includes('quick-snack') && (hasTag(item, 'snack') || hasTag(item, 'beverage'))) {
      score.push(12);
      reasons.add('perfect for a quick bite');
    }
    if (p.occasions.includes('everyday') && hasTag(item, 'main-course')) {
      score.push(10);
      reasons.add('a satisfying everyday meal');
    }
    if (p.occasions.includes('family') && hasTag(item, 'main-course') && item.price >= 129) {
      score.push(8);
      reasons.add('easy to share at the table');
    }
    if (p.occasions.includes('celebration') && item.price >= 149) {
      score.push(8);
      reasons.add('feels like a treat');
    }
    // note signals (SAFE handling — see contracts at top of file)
    if (/\bprotein\b/.test(noteLower) && hasTag(item, 'high-protein')) {
      score.push(15);
      reasons.add('a protein-rich pick (paneer)');
    }
    if (/(diabet|low sugar|less sugar|sugar[- ]free|no sugar)/.test(noteLower) && hasTag(item, 'sweet')) {
      score.push(-30); // sweet items downranked for low-sugar notes
    }
    if (/\b(spicy|hot food)\b/.test(noteLower) && spicy) score.push(10);
    if (/\bmild\b/.test(noteLower) && !spicy) score.push(8);
    // generic note overlap with real item text (small boost, capped)
    const stop = new Set(['food', 'meal', 'like', 'love', 'prefer', 'want', 'need', 'please', 'something', 'with', 'have', 'your', 'their', 'avoid']);
    const words = noteLower.split(/[^a-z]+/).filter((w) => w.length >= 4 && !stop.has(w));
    if (words.length) {
      const text = `${item.name} ${item.description}`.toLowerCase();
      const overlap = words.filter((w) => text.includes(w)).length;
      if (overlap > 0) {
        score.push(Math.min(9, overlap * 3));
        reasons.add('matches details in your note');
      }
    }

    return { item, score: score.reduce((a, b) => a + b, 0), reasons };
  });

  const positive = scored
    .filter((s) => s.score > 0)
    .sort((a, b) => b.score - a.score || a.item.price - b.item.price)
    .map((s) => ({ item: s.item, score: s.score, reasons: [...s.reasons] }));

  if (positive.length > 0) {
    return { recommendations: positive, advisories, excludedCount, source: 'rules' };
  }

  // No positive matches — honest fallback: a few available favourites, clearly
  // labelled as generic rather than personalized.
  const fallback = candidates.slice(0, 3).map((item) => ({
    item,
    score: 0,
    reasons: ['a popular pick from our menu'],
  }));
  return {
    recommendations: fallback,
    advisories: [...advisories, 'No exact matches for that combination — here are a few menu favourites instead.'],
    excludedCount,
    source: 'rules',
    emptyReason: 'no-exact-matches',
  };
}

// ---------------------------------------------------------------- AI hook (optional)

const AI_TIMEOUT_MS = 3000;

/** Preference labels safe to send to an external AI service (NO free-text note). */
function preferenceLabels(prefs: GuestPreferences): string[] {
  const p = { ...EMPTY_PREFERENCES, ...prefs };
  const labels: string[] = [];
  if (p.dietStyles.length) labels.push(`diet: ${p.dietStyles.join(', ')}`);
  if (p.cuisines.length) labels.push(`cuisines: ${p.cuisines.join(', ')}`);
  if (p.favoriteCategories.length) labels.push(`favourite sections (ids): ${p.favoriteCategories.join(', ')}`);
  if (p.spiceLevels.length) labels.push(`spice: ${p.spiceLevels.join(', ')}`);
  if (p.tasteProfile.length) labels.push(`taste: ${p.tasteProfile.join(', ')}`);
  if (p.beverages.length) labels.push(`beverages: ${p.beverages.join(', ')}`);
  if (p.exclusions.length) labels.push(`exclude: ${p.exclusions.join(', ')}`);
  if (p.budget.length) labels.push(`budget: ${p.budget.join(', ')}`);
  if (p.occasions.length) labels.push(`occasion: ${p.occasions.join(', ')}`);
  return labels;
}

/**
 * Recommendations with the optional AI layer. When
 * VITE_AI_RECOMMENDATION_URL is unset (the current deployment) this is a
 * pure rule-based call. When set, the service may only REORDER the real
 * candidates and rewrite explanations — invalid item ids are rejected, and
 * any error/timeout falls back to the rule-based result.
 */
export async function getRecommendations(
  prefs: GuestPreferences,
  items: GuestMenuItem[],
): Promise<RecommendationResult> {
  const rules = recommendItems(prefs, items);
  const aiUrl = (import.meta.env.VITE_AI_RECOMMENDATION_URL as string | undefined)?.trim();
  if (!aiUrl || rules.recommendations.length === 0) return rules;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), AI_TIMEOUT_MS);
  try {
    const res = await fetch(aiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
      body: JSON.stringify({
        preferenceLabels: preferenceLabels(prefs),
        candidates: rules.recommendations.slice(0, 8).map((r) => ({
          id: r.item.id,
          name: r.item.name,
          category: r.item.categoryName,
          price: r.item.price,
        })),
      }),
    });
    if (!res.ok) throw new Error(`AI endpoint HTTP ${res.status}`);
    const data = (await res.json()) as { picks?: { id?: unknown; reason?: unknown }[] };
    if (!Array.isArray(data.picks) || data.picks.length === 0) throw new Error('AI endpoint returned no picks');

    const byId = new Map(rules.recommendations.map((r) => [r.item.id, r]));
    const aiPicks: Recommendation[] = [];
    for (const pick of data.picks) {
      const id = typeof pick?.id === 'number' ? pick.id : Number(pick?.id);
      const base = byId.get(id);
      if (!base) continue; // reject invented/unknown ids — grounding rule
      aiPicks.push({
        item: base.item,
        score: base.score,
        reasons: [typeof pick.reason === 'string' && pick.reason.trim() ? pick.reason.trim().slice(0, 240) : base.reasons[0] ?? 'a match for your taste'],
      });
      if (aiPicks.length >= 4) break;
    }
    if (aiPicks.length === 0) throw new Error('AI picks did not reference real menu items');

    return {
      ...rules,
      recommendations: aiPicks,
      source: 'ai',
      advisories: rules.advisories.length ? rules.advisories : [],
    };
  } catch {
    return { ...rules, aiNote: 'AI service unavailable — showing smart matches from our menu.' };
  } finally {
    clearTimeout(timer);
  }
}
