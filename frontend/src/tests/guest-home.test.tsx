import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GuestHomePage from '../pages/GuestHomePage';
import { AuthProvider } from '../auth/AuthContext';
import {
  loadGuestPrefRecord,
  PREFS_STORAGE_KEY,
  savePreferences,
} from '../guest/preferenceStore';
import type { GuestPreferences } from '../guest/types';

function renderHome() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <GuestHomePage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

/** Waits until the homepage menu preview has rendered real items. */
async function awaitMenuPreview() {
  await waitFor(
    () => expect(document.querySelectorAll('.guest-section-preview [data-item-id]').length).toBeGreaterThan(0),
    { timeout: 4000 },
  );
}

/** Scoped queries: item names legitimately appear in several homepage sections. */
function modal() {
  return within(screen.getByRole('dialog'));
}

const coffeePrefs: GuestPreferences = {
  dietStyles: [], cuisines: [], favoriteCategories: [8], spiceLevels: [],
  tasteProfile: [], beverages: ['coffee'], exclusions: [], budget: [],
  occasions: [], note: '',
};

describe('GuestHomePage — first-visit & returning-guest journeys', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows the preference modal on a first eligible visit', async () => {
    renderHome();
    const dialog = await screen.findByRole('dialog', { name: /Welcome to Brew Haven/i }, { timeout: 3000 });
    expect(dialog).toBeTruthy();
    expect(modal().getAllByRole('checkbox').length).toBeGreaterThan(5);
  });

  it('skip works immediately and is remembered (no modal loop)', async () => {
    const { unmount } = renderHome();
    await screen.findByRole('dialog', { name: /Welcome to Brew Haven/i }, { timeout: 3000 });
    fireEvent.click(modal().getByRole('button', { name: /Skip for now/i }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(loadGuestPrefRecord()?.status).toBe('skipped');

    // Re-visit: homepage renders normally, NO modal, menu fully browsable.
    unmount();
    renderHome();
    await awaitMenuPreview();
    await new Promise((r) => setTimeout(r, 900)); // well past the 500ms first-visit timer
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('saving preferences updates the homepage instantly (no reload)', async () => {
    renderHome();
    await screen.findByRole('dialog', { name: /Welcome to Brew Haven/i }, { timeout: 3000 });

    fireEvent.click(modal().getByRole('checkbox', { name: /^Vegetarian/ }));
    fireEvent.click(modal().getByRole('button', { name: /Next/ }));
    fireEvent.click(modal().getByRole('checkbox', { name: /^Coffee/ }));
    fireEvent.click(modal().getByRole('button', { name: /Next/ }));
    fireEvent.click(modal().getByRole('button', { name: /Save preferences/i }));

    // Welcome-back strip + personalized section appear without a reload.
    expect(await screen.findByText(/Welcome back!/i, {}, { timeout: 3000 })).toBeTruthy();
    expect(await screen.findByText('Your personal menu matches', {}, { timeout: 3000 })).toBeTruthy();
    // Cold Coffee (category 8 = saved favourite) leads the personalized picks.
    const firstRec = document.querySelector('.guest-section-recs [data-item-id]') as HTMLElement;
    expect(firstRec.getAttribute('data-item-id')).toBe('8');
    // Nav switches to edit affordance.
    expect(screen.getAllByRole('button', { name: /^Edit preferences$/i }).length).toBeGreaterThan(0);
  });

  it('a skipped guest gets the normal homepage with an explicit personalize affordance', async () => {
    localStorage.setItem(
      PREFS_STORAGE_KEY,
      JSON.stringify({ version: 1, status: 'skipped', preferences: null, updatedAt: '2026-10-21T00:00:00Z' }),
    );
    renderHome();
    await awaitMenuPreview();
    expect(screen.queryByRole('dialog')).toBeNull();
    // Nav + hero both offer explicit personalization (never forced).
    expect(screen.getAllByRole('button', { name: /Personalize my picks/i }).length).toBeGreaterThan(0);
  });

  it('reopening from the nav restores and re-saves edited preferences', async () => {
    savePreferences(coffeePrefs);
    renderHome();
    await screen.findByText(/Welcome back!/i, {}, { timeout: 3000 });
    // The welcome-back strip's Edit button opens the modal in edit mode.
    fireEvent.click(screen.getAllByRole('button', { name: /Edit preferences/i })[0]);

    const dialog = await screen.findByRole('dialog', { name: /Edit your preferences/i });
    expect(dialog).toBeTruthy();
    // Saved selections are restored (Cold Coffee category checked on step 1).
    const coffeeCat = modal().getByRole('checkbox', { name: 'Cold Coffee' }) as HTMLInputElement;
    expect(coffeeCat.checked).toBe(true);

    // Deselect, walk to the end, save.
    fireEvent.click(coffeeCat);
    fireEvent.click(modal().getByRole('button', { name: /Next/ }));
    fireEvent.click(modal().getByRole('button', { name: /Next/ }));
    fireEvent.click(modal().getByRole('button', { name: /Save preferences/i }));
    await waitFor(() => {
      const rec = loadGuestPrefRecord();
      expect(rec?.status).toBe('saved');
      expect(rec?.preferences?.favoriteCategories).toEqual([]);
      expect(rec?.preferences?.beverages).toEqual(['coffee']);
    });
  });

  it('clearing preferences returns the guest to the plain homepage', async () => {
    savePreferences(coffeePrefs);
    renderHome();
    await screen.findByText(/Welcome back!/i, {}, { timeout: 3000 });
    fireEvent.click(screen.getByRole('button', { name: /Clear preferences/i }));
    await waitFor(() => expect(screen.queryByText(/Welcome back!/i)).toBeNull());
    expect(loadGuestPrefRecord()).toBeNull();
  });

  it('rendering never crashes even with corrupt preference storage', async () => {
    localStorage.setItem(PREFS_STORAGE_KEY, '###not-json###');
    renderHome();
    // Page renders with menu + first-visit modal (corrupt record = fresh visit).
    expect(await screen.findByRole('dialog', { name: /Welcome to Brew Haven/i }, { timeout: 3000 })).toBeTruthy();
    await awaitMenuPreview();
  });

  it('renders the signature carousel with real dishes and prices', async () => {
    renderHome();
    await awaitMenuPreview();
    const carousel = document.querySelector('.guest-hero-carousel') as HTMLElement;
    expect(carousel.textContent).toContain('Veggie Pizza');
    expect(carousel.textContent).toContain('Masala Dosa');
    expect(carousel.textContent).toContain('Litti Chokha');
    expect(carousel.textContent).toContain('₹199');
  });

  it('shows the restaurant-selected favourites section with an honest label', async () => {
    renderHome();
    await awaitMenuPreview();
    expect(screen.getByText(/Restaurant-selected favourites/i)).toBeTruthy();
    expect(screen.getByText(/not live sales statistics/i)).toBeTruthy();
  });
});
