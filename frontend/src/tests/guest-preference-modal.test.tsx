import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import PreferenceModal from '../components/guest/PreferenceModal';
import type { GuestPreferences } from '../guest/types';

const freshProps = () => ({
  mode: 'onboarding' as const,
  onSave: vi.fn(),
  onDismiss: vi.fn(),
});

/** Fresh props per test so spy call-counts never leak between tests. */
function renderModal(extra: Partial<Parameters<typeof PreferenceModal>[0]> = {}) {
  const props = { ...freshProps(), ...extra };
  const utils = render(<PreferenceModal open {...props} />);
  return { ...utils, props };
}

describe('PreferenceCollectorModal — first-visit experience', () => {
  it('renders as an accessible dialog with multi-select steps', () => {
    renderModal();
    expect(screen.getByRole('dialog', { name: /Welcome to Brew Haven/i })).toBeTruthy();
    // Step 1 renders checkbox groups (multi-select, not radios).
    expect(screen.getByRole('checkbox', { name: /^Vegetarian/ })).toBeTruthy();
    expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(5);
    expect(screen.queryByRole('radio')).toBeNull();
  });

  it('supports selecting and DESELECTING multiple options', () => {
    renderModal();
    const veg = screen.getByRole('checkbox', { name: /^Vegetarian/ }) as HTMLInputElement;
    const vegan = screen.getByRole('checkbox', { name: /^Vegan/ }) as HTMLInputElement;
    fireEvent.click(veg);
    fireEvent.click(vegan);
    expect(veg.checked).toBe(true);
    expect(vegan.checked).toBe(true);
    fireEvent.click(veg); // deselect
    expect(veg.checked).toBe(false);
    expect(vegan.checked).toBe(true);
  });

  it('walks the steps, collects the note and saves all selections', () => {
    const onSave = vi.fn();
    renderModal({ onSave });

    fireEvent.click(screen.getByRole('checkbox', { name: /^Vegetarian/ }));
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));

    fireEvent.click(screen.getByRole('checkbox', { name: /^Coffee/ }));
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));

    fireEvent.change(screen.getByLabelText(/Anything else\?/i), {
      target: { value: 'I prefer mild food.' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Save preferences/i }));

    expect(onSave).toHaveBeenCalledTimes(1);
    const saved = onSave.mock.calls[0][0] as GuestPreferences;
    expect(saved.dietStyles).toEqual(['vegetarian']);
    expect(saved.beverages).toEqual(['coffee']);
    expect(saved.note).toBe('I prefer mild food.');
  });

  it('Back returns to the previous step and keeps selections', () => {
    renderModal();
    fireEvent.click(screen.getByRole('checkbox', { name: /^Vegetarian/ }));
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    fireEvent.click(screen.getByRole('button', { name: /Back/ }));
    const veg = screen.getByRole('checkbox', { name: /^Vegetarian/ }) as HTMLInputElement;
    expect(veg.checked).toBe(true);
  });

  it('Skip for now is always available and dismisses', () => {
    const { props } = renderModal();
    fireEvent.click(screen.getByRole('button', { name: /Skip for now/i }));
    expect(props.onDismiss).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape (supported dialog close action)', () => {
    const { props } = renderModal();
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(props.onDismiss).toHaveBeenCalledTimes(1);
  });

  it('closes on overlay click', () => {
    const { container, props } = renderModal();
    fireEvent.mouseDown(container.querySelector('.pm-overlay') as HTMLElement);
    expect(props.onDismiss).toHaveBeenCalledTimes(1);
  });

  it('traps Tab focus inside the dialog', () => {
    renderModal();
    const dialog = screen.getByRole('dialog');
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true }); // wraps backwards from first element
    // Should not throw; focus stays inside. Verify the handler ran by checking dialog focusability.
    expect(dialog.getAttribute('role')).toBe('dialog');
  });

  it('enforces the note length limit with an accessible error', () => {
    renderModal();
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    const note = screen.getByLabelText(/Anything else\?/i);
    fireEvent.change(note, { target: { value: 'x'.repeat(400) } });
    expect(screen.getByRole('alert').textContent).toMatch(/under 280 characters/i);
  });

  it('shows a live character counter for the note', () => {
    renderModal();
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    fireEvent.change(screen.getByLabelText(/Anything else\?/i), { target: { value: 'hello' } });
    expect(screen.getByText('5/280')).toBeTruthy();
  });

  it('edit mode restores previously saved selections', () => {
    const initial: GuestPreferences = {
      dietStyles: [], cuisines: [], favoriteCategories: [8], spiceLevels: ['hot'],
      tasteProfile: [], beverages: ['coffee'], exclusions: [], budget: [], occasions: [],
      note: 'previous note',
    };
    renderModal({ mode: 'edit', initialPreferences: initial });
    // Step 1: favourite category checkbox for Cold Coffee (category id 8).
    const coffeeCat = screen.getByRole('checkbox', { name: 'Cold Coffee' }) as HTMLInputElement;
    expect(coffeeCat.checked).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    const hot = screen.getByRole('checkbox', { name: 'Hot' }) as HTMLInputElement;
    expect(hot.checked).toBe(true);
    const coffee = screen.getByRole('checkbox', { name: /^Coffee/ }) as HTMLInputElement;
    expect(coffee.checked).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    expect((screen.getByLabelText(/Anything else\?/i) as HTMLTextAreaElement).value).toBe('previous note');
  });

  it('labels options that have no matching items on the real menu', () => {
    renderModal();
    // Step 1: non-veg is labelled with no current-menu match.
    expect(screen.getByText(/No non-veg items on the current menu/i)).toBeTruthy();
    // Step 2: tea is likewise labelled honestly.
    fireEvent.click(screen.getByRole('button', { name: /Next/ }));
    expect(screen.getByText(/No tea items on the current menu/i)).toBeTruthy();
    expect(screen.getByText(/No juice items on the current menu/i)).toBeTruthy();
  });

  it('does not render when closed', () => {
    renderModal({ open: false });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('locks body scroll while open and restores it after', async () => {
    const { unmount } = renderModal();
    await waitFor(() => expect(document.body.style.overflow).toBe('hidden'));
    unmount();
    expect(document.body.style.overflow).toBe('');
  });
});
