import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SignatureCarousel from '../components/guest/SignatureCarousel';
import { resolveFeatured } from '../guest/featured';
import { MENU_SNAPSHOT } from '../guest/menuSnapshot';

function renderCarousel(ids: number[]) {
  return render(
    <MemoryRouter>
      <SignatureCarousel featured={resolveFeatured(MENU_SNAPSHOT.items, ids)} />
    </MemoryRouter>,
  );
}

describe('SignatureCarousel — rotation and controls', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('renders real signature items with prices and CTAs', () => {
    renderCarousel([1, 2]);
    expect(screen.getByText('Veggie Pizza')).toBeTruthy();
    expect(screen.getByText('Masala Dosa')).toBeTruthy();
    expect(screen.getByText('₹199')).toBeTruthy(); // real backend price
    expect(screen.getByRole('link', { name: 'View item' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Explore menu' }).getAttribute('href')).toBe('/menu');
  });

  it('auto-advances between featured items', () => {
    renderCarousel([1, 2]);
    const first = screen.getByRole('tab', { name: 'Show Veggie Pizza' });
    const second = screen.getByRole('tab', { name: 'Show Masala Dosa' });
    expect(first.className).toContain('sc-dot-active');
    act(() => {
      vi.advanceTimersByTime(6000);
    });
    expect(second.className).toContain('sc-dot-active');
    expect(first.className).not.toContain('sc-dot-active');
  });

  it('supports manual previous/next navigation', () => {
    renderCarousel([1, 2]);
    fireEvent.click(screen.getByRole('button', { name: 'Next signature dish' }));
    expect(screen.getByRole('tab', { name: 'Show Masala Dosa' }).className).toContain('sc-dot-active');
    fireEvent.click(screen.getByRole('button', { name: 'Previous signature dish' }));
    expect(screen.getByRole('tab', { name: 'Show Veggie Pizza' }).className).toContain('sc-dot-active');
  });

  it('dot navigation jumps directly to an item', () => {
    renderCarousel([1, 2, 4]);
    fireEvent.click(screen.getByRole('tab', { name: 'Show Litti Chokha' }));
    expect(screen.getByRole('tab', { name: 'Show Litti Chokha' }).className).toContain('sc-dot-active');
  });

  it('pauses auto-advance on hover and resumes on leave', () => {
    renderCarousel([1, 2]);
    const region = screen.getByRole('region', { name: /signature dishes/i });
    fireEvent.mouseEnter(region);
    act(() => {
      vi.advanceTimersByTime(20000);
    });
    expect(screen.getByRole('tab', { name: 'Show Veggie Pizza' }).className).toContain('sc-dot-active');
    fireEvent.mouseLeave(region);
    act(() => {
      vi.advanceTimersByTime(6000);
    });
    expect(screen.getByRole('tab', { name: 'Show Masala Dosa' }).className).toContain('sc-dot-active');
  });

  it('NEVER auto-advances under prefers-reduced-motion', () => {
    // jsdom lacks matchMedia by default; provide one that reports reduced motion.
    const original = window.matchMedia;
    window.matchMedia = vi.fn().mockReturnValue({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    });
    renderCarousel([1, 2]);
    act(() => {
      vi.advanceTimersByTime(60000);
    });
    expect(screen.getByRole('tab', { name: 'Show Veggie Pizza' }).className).toContain('sc-dot-active');
    // Restore so later tests get a clean (absent-in-jsdom) matchMedia.
    window.matchMedia = original;
  });

  it('shows no rotation controls for a single item', () => {
    renderCarousel([1]);
    expect(screen.queryByRole('button', { name: /signature dish/i })).toBeNull();
    expect(screen.queryByRole('tab')).toBeNull();
  });

  it('renders nothing when no featured items resolve (all unavailable/missing)', () => {
    const { container } = renderCarousel([9999]);
    expect(container.querySelector('.sc-root')).toBeNull();
  });

  it('CTA links to the real item in the menu', () => {
    renderCarousel([1, 2]);
    expect(screen.getByRole('link', { name: 'View item' }).getAttribute('href')).toBe('/menu?item=1');
    fireEvent.click(screen.getByRole('button', { name: 'Next signature dish' }));
    expect(screen.getByRole('link', { name: 'View item' }).getAttribute('href')).toBe('/menu?item=2');
  });

  it('excludes unavailable items from the rotation (filtered upstream by resolveFeatured)', () => {
    const unavailable = MENU_SNAPSHOT.items.map((i) =>
      i.id === 8 ? { ...i, status: 'false' as const } : i,
    );
    const featured = resolveFeatured(unavailable, [1, 8]);
    expect(featured.map((f) => f.item.id)).toEqual([1]); // Cold Coffee dropped
  });
});
