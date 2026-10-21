import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getRecommendations,
  noteMentionsHealth,
  recommendItems,
} from '../guest/recommendations';
import { MENU_SNAPSHOT, type GuestMenuItem } from '../guest/menuSnapshot';
import type { GuestPreferences } from '../guest/types';

const prefs = (over: Partial<GuestPreferences> = {}): GuestPreferences => ({
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
  ...over,
});

const realIds = () => MENU_SNAPSHOT.items.map((i) => i.id);
const byId = (id: number) => MENU_SNAPSHOT.items.find((i) => i.id === id) as GuestMenuItem;

describe('recommendation engine — grounding in REAL menu data', () => {
  it('only ever recommends actual menu items', () => {
    const result = recommendItems(prefs({ favoriteCategories: [8, 9], beverages: ['coffee', 'lassi'] }), MENU_SNAPSHOT.items);
    expect(result.recommendations.length).toBeGreaterThan(0);
    for (const rec of result.recommendations) {
      expect(realIds()).toContain(rec.item.id);
    }
  });

  it('recommendations change when preferences change', () => {
    const coffeeFirst = recommendItems(prefs({ favoriteCategories: [8] }), MENU_SNAPSHOT.items);
    const samosaFirst = recommendItems(prefs({ favoriteCategories: [3] }), MENU_SNAPSHOT.items);
    expect(coffeeFirst.recommendations[0].item.id).toBe(8); // Cold Coffee
    expect(samosaFirst.recommendations[0].item.id).toBe(3); // Paneer Samosa
  });

  it('respects the selected budget band (real prices)', () => {
    const result = recommendItems(prefs({ budget: ['under-100'] }), MENU_SNAPSHOT.items);
    expect(result.recommendations.length).toBeGreaterThan(0);
    for (const rec of result.recommendations) {
      if (rec.score > 0) expect(rec.item.price).toBeLessThanOrEqual(100);
    }
  });

  it('honours spice preferences against real descriptions', () => {
    const hot = recommendItems(prefs({ spiceLevels: ['hot'] }), MENU_SNAPSHOT.items);
    const spicyIds = MENU_SNAPSHOT.items.filter((i) => i.tags.some((t) => t.kind === 'spicy')).map((i) => i.id);
    expect(spicyIds).toContain(hot.recommendations[0].item.id); // a spicy item leads
  });

  it('respects verified dairy exclusions and reports the exclusion count', () => {
    const result = recommendItems(prefs({ exclusions: ['dairy'] }), MENU_SNAPSHOT.items);
    expect(result.excludedCount).toBeGreaterThan(0);
    for (const rec of result.recommendations) {
      const hasVerifiedDairy = rec.item.tags.some((t) => t.kind === 'dairy' && t.verified);
      expect(hasVerifiedDairy).toBe(false);
    }
    expect(result.advisories.some((a) => /excluded for you/i.test(a))).toBe(true);
  });

  it('never recommends unavailable items even when they would score highest', () => {
    const withUnavailable = [...MENU_SNAPSHOT.items, { ...byId(8), id: 99, name: 'Offline Coffee', status: 'false' as const }];
    const result = recommendItems(prefs({ favoriteCategories: [8], beverages: ['coffee'] }), withUnavailable);
    expect(result.recommendations.some((r) => r.item.id === 99)).toBe(false);
    expect(result.recommendations.some((r) => r.item.id === 8)).toBe(true);
  });

  it('falls back honestly when nothing matches (tea + non-veg only)', () => {
    const result = recommendItems(
      prefs({ beverages: ['tea'], dietStyles: ['non-vegetarian'] }),
      MENU_SNAPSHOT.items,
    );
    expect(result.emptyReason).toBe('no-exact-matches');
    expect(result.advisories.some((a) => /no non-vegetarian items/i.test(a))).toBe(true);
    // fallback picks are still REAL items
    for (const rec of result.recommendations) expect(realIds()).toContain(rec.item.id);
  });

  it('advises (never claims) for gluten and nut exclusions — no verified data', () => {
    const result = recommendItems(prefs({ exclusions: ['gluten', 'nuts'] }), MENU_SNAPSHOT.items);
    expect(result.advisories.some((a) => /cannot verify gluten/i.test(a))).toBe(true);
    expect(result.advisories.some((a) => /nut content is not verified/i.test(a))).toBe(true);
  });
});

describe('recommendation engine — note handling & health safety', () => {
  it('detects health-related notes', () => {
    expect(noteMentionsHealth('I have diabetes and need to be careful')).toBe(true);
    expect(noteMentionsHealth('allergic to peanuts')).toBe(true);
    expect(noteMentionsHealth('digestive discomfort')).toBe(true);
    expect(noteMentionsHealth('just craving something cheesy')).toBe(false);
  });

  it('adds a staff-confirmation advisory for health notes — no medical claims', () => {
    const result = recommendItems(prefs({ note: 'I have diabetes and need to be careful about what I eat.' }), MENU_SNAPSHOT.items);
    const advisory = result.advisories.join(' ');
    expect(advisory).toContain('not medical advice');
    expect(advisory).toContain('confirm ingredients with our staff');
    // The engine must never output treatment/cure/safety claims.
    const allText = [...result.advisories, ...result.recommendations.flatMap((r) => r.reasons)].join(' ').toLowerCase();
    expect(allText).not.toMatch(/cures?|treats?|medically safe|guaranteed safe/);
  });

  it('downranks sweet items for low-sugar notes', () => {
    const result = recommendItems(prefs({ note: 'low sugar please' }), MENU_SNAPSHOT.items);
    const sweetIds = MENU_SNAPSHOT.items.filter((i) => i.tags.some((t) => t.kind === 'sweet')).map((i) => i.id);
    expect(result.recommendations[0].item.id).not.toBe(10); // Mango Lassi (sweet) must not lead
    expect(result.recommendations.filter((r) => sweetIds.includes(r.item.id) && r.score > 0)).toHaveLength(0);
  });

  it('boosts high-protein picks for protein notes', () => {
    const result = recommendItems(prefs({ note: 'I would like a high-protein meal' }), MENU_SNAPSHOT.items);
    expect(result.recommendations[0].item.id).toBe(3); // Paneer Samosa (verified paneer = high-protein)
    expect(result.recommendations[0].reasons.join(' ')).toMatch(/protein/i);
  });

  it('applies a dairy exclusion when the note mentions dairy', () => {
    const result = recommendItems(prefs({ note: 'I avoid dairy.' }), MENU_SNAPSHOT.items);
    for (const rec of result.recommendations) {
      expect(rec.item.tags.some((t) => t.kind === 'dairy' && t.verified)).toBe(false);
    }
  });

  it('caps note influence and safely handles long notes', () => {
    const long = `mild ${'and tasty '.repeat(60)}`;
    const result = recommendItems(prefs({ note: long }), MENU_SNAPSHOT.items);
    expect(Array.isArray(result.recommendations)).toBe(true); // no crash, still grounded
  });
});

describe('AI hook — optional, grounded, fail-safe', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('uses the rule-based matcher (source "rules") when no AI endpoint is configured', async () => {
    const result = await getRecommendations(prefs({ favoriteCategories: [8] }), MENU_SNAPSHOT.items);
    expect(result.source).toBe('rules');
    expect(result.recommendations[0].item.id).toBe(8);
  });

  it('falls back to rules when the AI endpoint fails (timeout/error)', async () => {
    vi.stubEnv('VITE_AI_RECOMMENDATION_URL', 'http://ai.invalid/recommend');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    const result = await getRecommendations(prefs({ favoriteCategories: [8] }), MENU_SNAPSHOT.items);
    expect(result.source).toBe('rules');
    expect(result.aiNote).toMatch(/AI service unavailable/i);
    expect(result.recommendations[0].item.id).toBe(8);
  });

  it('rejects AI picks that do not reference real menu items', async () => {
    vi.stubEnv('VITE_AI_RECOMMENDATION_URL', 'http://ai.invalid/recommend');
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ picks: [{ id: 4242, reason: 'made up dish' }] }),
      }),
    );
    const result = await getRecommendations(prefs({ favoriteCategories: [8] }), MENU_SNAPSHOT.items);
    expect(result.source).toBe('rules'); // fell back — invented id rejected
    expect(result.aiNote).toBeDefined();
  });

  it('accepts an AI explanation only for real candidate items', async () => {
    vi.stubEnv('VITE_AI_RECOMMENDATION_URL', 'http://ai.invalid/recommend');
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async (_url: string, init?: RequestInit) => {
        // Verify the guest NOTE is never transmitted to the external service.
        const body = JSON.parse(String(init?.body)) as { preferenceLabels: string[] };
        expect(JSON.stringify(body)).not.toMatch(/I have diabetes/);
        return {
          ok: true,
          json: async () => ({ picks: [{ id: 8, reason: 'Because you love coffee!' }] }),
        };
      }),
    );
    const result = await getRecommendations(
      prefs({ favoriteCategories: [8], note: 'I have diabetes' }),
      MENU_SNAPSHOT.items,
    );
    expect(result.source).toBe('ai');
    expect(result.recommendations[0].item.id).toBe(8);
    expect(result.recommendations[0].reasons[0]).toBe('Because you love coffee!');
  });
});
