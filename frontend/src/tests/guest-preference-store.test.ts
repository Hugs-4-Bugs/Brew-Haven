import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearGuestPrefs,
  loadGuestPrefRecord,
  PREFS_EVENT,
  PREFS_STORAGE_KEY,
  savePreferences,
  skipOnboarding,
} from '../guest/preferenceStore';
import type { GuestPreferences } from '../guest/types';

const samplePrefs = (over: Partial<GuestPreferences> = {}): GuestPreferences => ({
  dietStyles: [],
  cuisines: [],
  favoriteCategories: [8],
  spiceLevels: [],
  tasteProfile: [],
  beverages: ['coffee'],
  exclusions: [],
  budget: ['100-150'],
  occasions: [],
  note: '',
  ...over,
});

describe('guest preference store — persistence semantics', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns null when nothing is stored (first eligible visit)', () => {
    expect(loadGuestPrefRecord()).toBeNull();
  });

  it('saves and restores preferences (status saved)', () => {
    const ok = savePreferences(samplePrefs({ note: 'I prefer mild food.' }));
    expect(ok).toBe(true);
    const rec = loadGuestPrefRecord();
    expect(rec?.status).toBe('saved');
    expect(rec?.preferences?.favoriteCategories).toEqual([8]);
    expect(rec?.preferences?.note).toBe('I prefer mild food.');
  });

  it('stores skipped as a DISTINCT state and restores it', () => {
    expect(skipOnboarding()).toBe(true);
    const rec = loadGuestPrefRecord();
    expect(rec?.status).toBe('skipped');
    expect(rec?.preferences).toBeNull();
  });

  it('clears the record entirely', () => {
    savePreferences(samplePrefs());
    expect(clearGuestPrefs()).toBe(true);
    expect(loadGuestPrefRecord()).toBeNull();
    expect(localStorage.getItem(PREFS_STORAGE_KEY)).toBeNull();
  });

  it('treats corrupt JSON gracefully (no crash, first-visit state)', () => {
    localStorage.setItem(PREFS_STORAGE_KEY, '{corrupt json!!');
    expect(loadGuestPrefRecord()).toBeNull();
  });

  it('rejects an unknown future schema version', () => {
    localStorage.setItem(PREFS_STORAGE_KEY, JSON.stringify({ version: 99, status: 'saved', preferences: {} }));
    expect(loadGuestPrefRecord()).toBeNull();
  });

  it('rejects records with an invalid status', () => {
    localStorage.setItem(
      PREFS_STORAGE_KEY,
      JSON.stringify({ version: 1, status: 'something-else', preferences: samplePrefs() }),
    );
    expect(loadGuestPrefRecord()).toBeNull();
  });

  it('sanitizes hostile preference payloads (non-arrays dropped, note capped)', () => {
    localStorage.setItem(
      PREFS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        status: 'saved',
        updatedAt: '2026-10-21T00:00:00Z',
        preferences: {
          dietStyles: 'not-an-array',
          favoriteCategories: [8, 'x', null, 3],
          beverages: ['coffee', 42],
          note: 'x'.repeat(500),
        },
      }),
    );
    const rec = loadGuestPrefRecord();
    expect(rec?.preferences?.dietStyles).toEqual([]);
    expect(rec?.preferences?.favoriteCategories).toEqual([8, 3]);
    expect(rec?.preferences?.beverages).toEqual(['coffee']);
    expect(rec?.preferences?.note.length).toBe(280); // NOTE_MAX_LENGTH
  });

  it('treats a saved record with completely empty preferences as first-visit', () => {
    localStorage.setItem(
      PREFS_STORAGE_KEY,
      JSON.stringify({ version: 1, status: 'saved', preferences: {}, updatedAt: '2026-10-21T00:00:00Z' }),
    );
    expect(loadGuestPrefRecord()).toBeNull();
  });

  it('never crashes when localStorage access throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    expect(loadGuestPrefRecord()).toBeNull();
    spy.mockRestore();
  });

  it('reports write failures instead of throwing', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded');
    });
    expect(savePreferences(samplePrefs())).toBe(false);
    expect(skipOnboarding()).toBe(false);
    spy.mockRestore();
  });

  it('dispatches a change event so pages update without reload', () => {
    const handler = vi.fn();
    window.addEventListener(PREFS_EVENT, handler);
    savePreferences(samplePrefs());
    expect(handler).toHaveBeenCalledTimes(1);
    skipOnboarding();
    expect(handler).toHaveBeenCalledTimes(2);
    clearGuestPrefs();
    expect(handler).toHaveBeenCalledTimes(3);
    window.removeEventListener(PREFS_EVENT, handler);
  });
});
