/**
 * Public guest homepage — first-visit onboarding, signature carousel,
 * personalized recommendations, curated favourites and a menu preview.
 *
 * Route: "/" (public; the authenticated app keeps its own routes —
 * /dashboard etc. remain protected exactly as before).
 *
 * Onboarding rules:
 *  - First eligible visit (no stored record) → preference modal appears once.
 *  - Skipped guests NEVER see the modal again unless they ask for it.
 *  - Saved guests get a welcome-back strip + personalized picks, no modal.
 *  - Saving/clearing updates the page instantly (storage event), no reload.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import GuestNav from '../components/guest/GuestNav';
import PreferenceModal from '../components/guest/PreferenceModal';
import SignatureCarousel from '../components/guest/SignatureCarousel';
import RecommendationsSection from '../components/guest/RecommendationsSection';
import MenuItemCard from '../components/guest/MenuItemCard';
import {
  clearGuestPrefs,
  loadGuestPrefRecord,
  PREFS_EVENT,
  savePreferences,
  skipOnboarding,
} from '../guest/preferenceStore';
import { resolveGuestMenu, type MenuSource } from '../guest/menuSnapshot';
import { POPULAR_ITEM_IDS, resolveFeatured, SIGNATURE_ITEM_IDS } from '../guest/featured';
import { pairingsFor } from '../guest/pairings';
import type { GuestPrefRecord } from '../guest/types';

export default function GuestHomePage(): JSX.Element {
  const [menu, setMenu] = useState<MenuSource>({ items: [], source: 'snapshot' });
  const [record, setRecord] = useState<GuestPrefRecord | null>(null);
  const [checkedStorage, setCheckedStorage] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState<'onboarding' | 'edit'>('onboarding');

  // Initial load: storage record + real menu data.
  useEffect(() => {
    const rec = loadGuestPrefRecord();
    setRecord(rec);
    setCheckedStorage(true);
    // First eligible visit only: no record (neither saved nor skipped).
    if (!rec) {
      const t = window.setTimeout(() => setModalOpen(true), 500);
      return () => window.clearTimeout(t);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    resolveGuestMenu().then((m) => {
      if (!cancelled) setMenu(m);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // React to preference changes without a page reload.
  useEffect(() => {
    const onChange = () => setRecord(loadGuestPrefRecord());
    window.addEventListener(PREFS_EVENT, onChange);
    return () => window.removeEventListener(PREFS_EVENT, onChange);
  }, []);

  const openPreferences = useCallback(() => {
    const rec = loadGuestPrefRecord();
    setModalMode(rec?.status === 'saved' ? 'edit' : 'onboarding');
    setModalOpen(true);
  }, []);

  const handleSave = useCallback((prefs: Parameters<typeof savePreferences>[0]) => {
    savePreferences(prefs);
    setModalOpen(false);
  }, []);

  const handleDismiss = useCallback(() => {
    // Onboarding dismissals are remembered as 'skipped' so guests are never
    // trapped in a modal loop. Editing cancellations keep the saved record.
    if (modalMode === 'onboarding' && !loadGuestPrefRecord()) skipOnboarding();
    setModalOpen(false);
  }, [modalMode]);

  const handleClearPrefs = useCallback(() => {
    clearGuestPrefs();
  }, []);

  const savedPrefs = record?.status === 'saved' ? record.preferences : null;

  const featured = useMemo(() => resolveFeatured(menu.items, SIGNATURE_ITEM_IDS), [menu.items]);
  const popular = useMemo(() => resolveFeatured(menu.items, POPULAR_ITEM_IDS), [menu.items]);
  const previewItems = useMemo(() => menu.items.filter((i) => i.status === 'true').slice(0, 6), [menu.items]);

  const prefSummary = useMemo(() => {
    if (!savedPrefs) return '';
    const parts: string[] = [];
    if (savedPrefs.favoriteCategories.length) parts.push(`${savedPrefs.favoriteCategories.length} favourite section${savedPrefs.favoriteCategories.length === 1 ? '' : 's'}`);
    if (savedPrefs.beverages.length) parts.push('beverage picks');
    if (savedPrefs.spiceLevels.length) parts.push('spice level');
    if (savedPrefs.budget.length) parts.push('a budget');
    return parts.length ? `We remember ${parts.join(', ')}.` : 'We remember your note.';
  }, [savedPrefs]);

  return (
    <div className="guest-page">
      <GuestNav onEditPreferences={openPreferences} prefRecord={record} />

      <PreferenceModal
        open={modalOpen}
        mode={modalMode}
        initialPreferences={modalMode === 'edit' ? (savedPrefs ?? undefined) : undefined}
        onSave={handleSave}
        onDismiss={handleDismiss}
      />

      <main>
        {/* ---------------------------------------------------------- hero */}
        <section className="guest-hero">
          <div className="guest-hero-inner">
            <div className="guest-hero-copy">
              <span className="guest-eyebrow">Fresh · Honest · Made to order</span>
              <h1 className="guest-hero-title">
                A warm table at <span className="guest-hero-accent">Brew Haven</span>
              </h1>
              <p className="guest-hero-sub">
                Honest Indian café classics, chilled coffee and sweet lassi — pick your favourites
                and we will point you straight at them.
              </p>
              <div className="guest-hero-actions">
                <Link to="/menu" className="btn btn-primary btn-lg">
                  Explore the menu
                </Link>
                {!savedPrefs && (
                  <button type="button" className="btn btn-outline btn-lg" onClick={openPreferences}>
                    ✨ Personalize my picks
                  </button>
                )}
              </div>
              {checkedStorage && !modalOpen && record === null && (
                <p className="guest-hero-hint">First time here? A quick 30-second taste quiz tailors everything.</p>
              )}
            </div>
            <div className="guest-hero-carousel">
              <SignatureCarousel featured={featured} />
            </div>
          </div>
        </section>

        {/* ------------------------------------------- returning guest strip */}
        {savedPrefs && (
          <section className="guest-welcomeback" aria-label="Welcome back">
            <div>
              <strong>Welcome back!</strong> {prefSummary}
            </div>
            <div className="guest-welcomeback-actions">
              <button type="button" className="btn btn-outline" onClick={openPreferences}>
                Edit preferences
              </button>
              <button type="button" className="btn btn-ghost" onClick={handleClearPrefs}>
                Clear preferences
              </button>
            </div>
          </section>
        )}

        {/* --------------------------------------------- recommendations */}
        {savedPrefs && menu.items.length > 0 && (
          <RecommendationsSection preferences={savedPrefs} items={menu.items} onEditPreferences={openPreferences} />
        )}

        {/* --------------------------------------------------- chef picks */}
        {popular.length > 0 && (
          <section className="guest-section" aria-labelledby="popular-title">
            <div className="guest-section-head">
              <div>
                <span className="guest-eyebrow">Chef’s selections</span>
                <h2 id="popular-title" className="guest-section-title">
                  Restaurant-selected favourites
                </h2>
                <p className="guest-section-sub">
                  Chosen by our kitchen team — not live sales statistics.
                </p>
              </div>
            </div>
            <div className="guest-grid">
              {popular.map((f) => (
                <MenuItemCard key={f.item.id} item={f.item} pairings={pairingsFor(f.item, menu.items)} />
              ))}
            </div>
          </section>
        )}

        {/* -------------------------------------------------- menu preview */}
        <section className="guest-section guest-section-preview" aria-labelledby="preview-title">
          <div className="guest-section-head">
            <div>
              <span className="guest-eyebrow">From the kitchen</span>
              <h2 id="preview-title" className="guest-section-title">
                A taste of our menu
              </h2>
              <p className="guest-section-sub">
                {menu.source === 'live'
                  ? 'Showing the live menu from our kitchen.'
                  : `Showing our published menu (updated ${menu.capturedAt ?? 'recently'}).`}
              </p>
            </div>
            <Link to="/menu" className="btn btn-outline">
              View the full menu →
            </Link>
          </div>
          {previewItems.length > 0 ? (
            <div className="guest-grid">
              {previewItems.map((item) => (
                <MenuItemCard key={item.id} item={item} />
              ))}
            </div>
          ) : (
            <p className="rec-empty">Our menu is being refreshed — please check back shortly.</p>
          )}
        </section>
      </main>

      <footer className="guest-footer">
        <div className="guest-footer-inner">
          <span className="guest-footer-brand">☕ Brew Haven</span>
          <nav aria-label="Footer">
            <Link to="/menu">Menu</Link>
            <Link to="/login">Staff sign in</Link>
            <Link to="/signup">Create account</Link>
          </nav>
          <p className="guest-footer-note">
            Prices and availability from our kitchen records
            {menu.source === 'snapshot' && ` (published ${menu.capturedAt ?? ''})`}. Dietary notes are
            suggestions, not medical advice — please confirm ingredients with our staff.
          </p>
        </div>
      </footer>
    </div>
  );
}
