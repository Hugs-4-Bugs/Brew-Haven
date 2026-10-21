/**
 * First-visit preference collector modal.
 *
 * Requirements implemented here:
 *  - Multi-step wizard over REAL menu categories (see guest/preferenceOptions.ts).
 *  - Multiple simultaneous selections via native checkboxes (select + deselect).
 *  - Optional free-text note (max 280 chars) with live counter + a11y errors.
 *  - "Save preferences" AND "Skip for now" — skipping always works, nothing is
 *    mandatory; guests reach the homepage/menu either way.
 *  - Escape / overlay click / X close (dismiss semantics decided by parent).
 *  - Focus trap + focus restore, body scroll lock, aria dialog semantics,
 *    full keyboard navigation, mobile-friendly sheet layout.
 *  - Editing mode restores previously saved selections.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { NOTE_MAX_LENGTH, type GuestPreferences } from '../../guest/types';
import { PREFERENCE_STEPS } from '../../guest/preferenceOptions';
import { EMPTY_PREFERENCES } from '../../guest/preferenceStore';

interface PreferenceModalProps {
  open: boolean;
  /** 'onboarding' = first visit; 'edit' = reopening with saved selections. */
  mode: 'onboarding' | 'edit';
  initialPreferences?: GuestPreferences;
  onSave: (prefs: GuestPreferences) => void;
  /** Skip button / Escape / overlay / X. Parent decides state semantics. */
  onDismiss: () => void;
}

const FOCUSABLE = 'button, input, textarea, select, a[href], [tabindex]:not([tabindex="-1"])';

export default function PreferenceModal({ open, mode, initialPreferences, onSave, onDismiss }: PreferenceModalProps): JSX.Element | null {
  const [step, setStep] = useState(0);
  const [prefs, setPrefs] = useState<GuestPreferences>(initialPreferences ?? EMPTY_PREFERENCES);
  const [noteError, setNoteError] = useState('');
  const dialogRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);

  const totalSteps = PREFERENCE_STEPS.length;

  // Reset when (re)opened.
  useEffect(() => {
    if (!open) return;
    setStep(0);
    setPrefs(initialPreferences ?? EMPTY_PREFERENCES);
    setNoteError('');
  }, [open, initialPreferences]);

  // Body scroll lock + focus management.
  useEffect(() => {
    if (!open) return;
    restoreFocusRef.current = document.activeElement as HTMLElement | null;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const node = dialogRef.current;
    if (node) {
      const first = node.querySelector<HTMLElement>(FOCUSABLE);
      (first ?? node).focus();
    }
    return () => {
      document.body.style.overflow = prevOverflow;
      restoreFocusRef.current?.focus?.();
    };
  }, [open]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onDismiss();
        return;
      }
      if (e.key !== 'Tab') return;
      const node = dialogRef.current;
      if (!node) return;
      const focusables = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
        (el) => !el.hasAttribute('disabled'),
      );
      if (focusables.length === 0) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    },
    [onDismiss],
  );

  const toggleListValue = (group: string, optionId: string, checked: boolean) => {
    setPrefs((prev) => {
      if (group === 'favoriteCategories') {
        const num = Number(optionId);
        const set = new Set(prev.favoriteCategories);
        if (checked) set.add(num);
        else set.delete(num);
        return { ...prev, favoriteCategories: [...set] };
      }
      const key = group as keyof Omit<GuestPreferences, 'favoriteCategories' | 'note'>;
      const set = new Set(prev[key] as string[]);
      if (checked) set.add(optionId);
      else set.delete(optionId);
      return { ...prev, [key]: [...set] } as GuestPreferences;
    });
  };

  const isChecked = (group: string, optionId: string): boolean => {
    if (group === 'favoriteCategories') return prefs.favoriteCategories.includes(Number(optionId));
    const key = group as keyof Omit<GuestPreferences, 'favoriteCategories' | 'note'>;
    return (prefs[key] as string[]).includes(optionId);
  };

  const handleNoteChange = (value: string) => {
    if (value.length > NOTE_MAX_LENGTH) {
      setNoteError(`Please keep your note under ${NOTE_MAX_LENGTH} characters.`);
      return;
    }
    setNoteError('');
    setPrefs((prev) => ({ ...prev, note: value }));
  };

  const handleSave = () => {
    const trimmed = prefs.note.trim().slice(0, NOTE_MAX_LENGTH);
    onSave({ ...prefs, note: trimmed });
  };

  const progress = Math.round(((step + 1) / totalSteps) * 100);
  const currentStep = PREFERENCE_STEPS[step];
  const isLast = step === totalSteps - 1;

  const noteCounter = useMemo(
    () => `${prefs.note.length}/${NOTE_MAX_LENGTH}`,
    [prefs.note.length],
  );

  if (!open) return null;

  return (
    <div
      className="pm-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onDismiss();
      }}
    >
      <div
        ref={dialogRef}
        className="pm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="pm-title"
        aria-describedby="pm-subtitle"
        onKeyDown={handleKeyDown}
        tabIndex={-1}
      >
        <header className="pm-header">
          <div>
            <h2 id="pm-title" className="pm-title">
              {mode === 'onboarding' ? 'Welcome to Brew Haven! 🍽️' : 'Edit your preferences'}
            </h2>
            <p id="pm-subtitle" className="pm-subtitle">
              {mode === 'onboarding'
                ? 'Tell us what you love — we will tailor your menu picks. You can skip this and browse everything.'
                : 'Update anything below — your picks refresh instantly.'}
            </p>
          </div>
          <button type="button" className="pm-close" aria-label="Close preferences" onClick={onDismiss}>
            ✕
          </button>
        </header>

        <div
          className="pm-progress"
          role="progressbar"
          aria-valuenow={progress}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`Step ${step + 1} of ${totalSteps}`}
        >
          <div className="pm-progress-fill" style={{ width: `${progress}%` }} />
          <span className="pm-progress-label">
            Step {step + 1} of {totalSteps}
          </span>
        </div>

        <div className="pm-body">
          <h3 className="pm-step-title">{currentStep.title}</h3>
          <p className="pm-step-subtitle">{currentStep.subtitle}</p>

          {currentStep.groups.map((group) => (
            <fieldset key={group.id as string} className="pm-group">
              <legend className="pm-group-title">{group.title}</legend>
              {group.hint && <p className="pm-group-hint">{group.hint}</p>}
              <div className="pm-options">
                {group.options.map((opt) => (
                  <label key={opt.id} className="pm-option">
                    <input
                      type="checkbox"
                      checked={isChecked(group.id as string, opt.id)}
                      onChange={(e) => toggleListValue(group.id as string, opt.id, e.target.checked)}
                    />
                    <span className="pm-option-label">
                      {opt.label}
                      {opt.menuNote && <small className="pm-option-note">{opt.menuNote}</small>}
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
          ))}

          {isLast && (
            <div className="pm-note">
              <label htmlFor="pm-note-input" className="pm-group-title">
                Anything else? <span className="pm-optional">(optional)</span>
              </label>
              <p className="pm-group-hint">
                Cravings, dietary needs or anything our staff should know. We use it only to shape
                suggestions — it is never medical advice.
              </p>
              <textarea
                id="pm-note-input"
                value={prefs.note}
                onChange={(e) => handleNoteChange(e.target.value)}
                maxLength={NOTE_MAX_LENGTH + 100}
                rows={3}
                placeholder="e.g. I prefer mild food, or I am looking for a high-protein meal"
                aria-describedby="pm-note-counter pm-note-error"
              />
              <div className="pm-note-meta">
                <span id="pm-note-counter" className="pm-note-counter">{noteCounter}</span>
                {noteError && (
                  <span id="pm-note-error" className="pm-note-error" role="alert">
                    {noteError}
                  </span>
                )}
              </div>
            </div>
          )}
        </div>

        <footer className="pm-footer">
          <button type="button" className="btn btn-ghost pm-skip" onClick={onDismiss}>
            {mode === 'onboarding' ? 'Skip for now' : 'Cancel'}
          </button>
          <div className="pm-footer-nav">
            {step > 0 && (
              <button type="button" className="btn btn-outline" onClick={() => setStep((s) => s - 1)}>
                ← Back
              </button>
            )}
            {!isLast ? (
              <button type="button" className="btn btn-primary" onClick={() => setStep((s) => s + 1)}>
                Next →
              </button>
            ) : (
              <button type="button" className="btn btn-primary" onClick={handleSave}>
                Save preferences
              </button>
            )}
          </div>
        </footer>
      </div>
    </div>
  );
}
