import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MenuPage from '../pages/MenuPage';
import { AuthProvider } from '../auth/AuthContext';
import { clearGuestPrefs, savePreferences } from '../guest/preferenceStore';
import type { GuestPreferences } from '../guest/types';

function renderMenu(initialEntry = '/menu') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <MenuPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const coffeePrefs = (over: Partial<GuestPreferences> = {}): GuestPreferences => ({
  dietStyles: [], cuisines: [], favoriteCategories: [8], spiceLevels: [],
  tasteProfile: [], beverages: ['coffee'], exclusions: [], budget: [],
  occasions: [], note: '', ...over,
});

describe('MenuPage — public menu with preference-aware browsing', () => {
  beforeEach(() => {
    localStorage.clear();
    clearGuestPrefs();
  });

  it('renders the full real menu for anonymous guests', async () => {
    renderMenu();
    // 10 curated snapshot dishes render as cards (names may also appear in
    // pairing hints, so assertions are scoped to menu cards).
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
    const ids = [...document.querySelectorAll('.menu-grid [data-item-id]')].map((el) =>
      Number(el.getAttribute('data-item-id')),
    );
    expect(ids).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it('filters by real category chips', async () => {
    renderMenu();
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
    fireEvent.click(screen.getByRole('button', { name: 'Lassi' }));
    const lassiCards = document.querySelectorAll('.menu-grid [data-item-id]');
    expect(lassiCards.length).toBe(1);
    expect(lassiCards[0].getAttribute('data-item-id')).toBe('10'); // Mango Lassi
    fireEvent.click(screen.getByRole('button', { name: 'All' }));
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
  });

  it('shows the taste toolbar only when preferences are saved', async () => {
    renderMenu();
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
    expect(screen.queryByLabelText(/Sort by my taste/i)).toBeNull();
  });

  it('sorts matches first and explains hidden items when filtering', async () => {
    savePreferences(coffeePrefs());
    renderMenu();
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));

    const sortToggle = screen.getByLabelText(/Sort by my taste/i) as HTMLInputElement;
    fireEvent.click(sortToggle);
    // First card in the grid is now the preference match (Cold Coffee, id 8).
    const firstCard = document.querySelector('.menu-grid [data-item-id]') as HTMLElement;
    expect(firstCard.getAttribute('data-item-id')).toBe('8');

    const onlyToggle = screen.getByLabelText(/Only show matches/i) as HTMLInputElement;
    fireEvent.click(onlyToggle);
    expect(screen.getByText(/hidden by your taste filter/i)).toBeTruthy();
    const cards = document.querySelectorAll('.menu-grid [data-item-id]');
    expect(cards.length).toBe(1);
    expect(cards[0].getAttribute('data-item-id')).toBe('8');

    fireEvent.click(screen.getByRole('button', { name: /Clear taste filters/i }));
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
  });

  it('keeps the full menu reachable (preferences never silently hide everything)', async () => {
    // A realistic "no positive matches" preference set: a dairy exclusion
    // removes verified-dairy items but leaves the rest score-neutral.
    savePreferences(coffeePrefs({ favoriteCategories: [], beverages: [], exclusions: ['dairy'] }));
    renderMenu();
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
    fireEvent.click(screen.getByLabelText(/Sort by my taste/i));
    // No positive matches at all → toggling "only matches" hides everything but says so.
    fireEvent.click(screen.getByLabelText(/Only show matches/i));
    expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(0);
    expect(screen.getByRole('button', { name: /Clear taste filters/i })).toBeTruthy();
    expect(screen.getByText(/Nothing matches your current taste filters/i)).toBeTruthy();
  });

  it('highlights the deep-linked ?item= card', async () => {
    renderMenu('/menu?item=8');
    await waitFor(() => {
      const card = document.querySelector('[data-item-id="8"]') as HTMLElement;
      expect(card).toBeTruthy();
      expect(card.className).toContain('mic-highlight');
    });
  });

  it('badges unavailable items honestly', async () => {
    renderMenu();
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
    // All snapshot items are currently available — the badge must NOT render.
    expect(screen.queryByText('Currently unavailable')).toBeNull();
  });

  it('links each card back to itself for deep-linking', async () => {
    renderMenu();
    await waitFor(() => expect(document.querySelectorAll('.menu-grid [data-item-id]').length).toBe(10));
    const pizzaCard = document.querySelector('[data-item-id="1"]') as HTMLElement;
    const link = pizzaCard.querySelector('a.mic-view') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/menu?item=1');
  });
});
