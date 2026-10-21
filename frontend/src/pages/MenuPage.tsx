/**
 * Public menu page — the complete real menu with preference-aware browsing.
 *
 *  - Full menu is ALWAYS reachable: preferences only SORT and (opt-in)
 *    FILTER; a visible counter explains what is hidden and one click clears it.
 *  - ?item=<id> deep link scrolls to and briefly highlights a card (used by
 *    the signature carousel and item cards).
 *  - Category chips filter by the real backend categories.
 *  - Unavailable items are still listed (that is the honest menu) but clearly
 *    badged and never recommended or featured.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import GuestNav from '../components/guest/GuestNav';
import MenuItemCard from '../components/guest/MenuItemCard';
import { loadGuestPrefRecord, PREFS_EVENT } from '../guest/preferenceStore';
import { resolveGuestMenu, type GuestMenuItem, type MenuSource } from '../guest/menuSnapshot';
import { recommendItems } from '../guest/recommendations';
import { pairingsFor } from '../guest/pairings';
import type { GuestPrefRecord } from '../guest/types';

export default function MenuPage(): JSX.Element {
  const [menu, setMenu] = useState<MenuSource>({ items: [], source: 'snapshot' });
  const [record, setRecord] = useState<GuestPrefRecord | null>(null);
  const [categoryFilter, setCategoryFilter] = useState<number | 'all'>('all');
  const [sortByTaste, setSortByTaste] = useState(false);
  const [onlyMatches, setOnlyMatches] = useState(false);
  const [params, setParams] = useSearchParams();
  const highlightId = params.get('item') ? Number(params.get('item')) : null;
  const gridRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setRecord(loadGuestPrefRecord());
    let cancelled = false;
    resolveGuestMenu().then((m) => {
      if (!cancelled) setMenu(m);
    });
    const onChange = () => setRecord(loadGuestPrefRecord());
    window.addEventListener(PREFS_EVENT, onChange);
    return () => {
      cancelled = true;
      window.removeEventListener(PREFS_EVENT, onChange);
    };
  }, []);

  // Scroll to deep-linked item once the grid has rendered.
  useEffect(() => {
    if (highlightId == null || menu.items.length === 0) return;
    const t = window.setTimeout(() => {
      const el = gridRef.current?.querySelector(`[data-item-id="${highlightId}"]`);
      el?.scrollIntoView?.({ behavior: 'smooth', block: 'center' }); // ?. guards jsdom (no scroll)
    }, 150);
    return () => window.clearTimeout(t);
  }, [highlightId, menu.items.length, categoryFilter, sortByTaste, onlyMatches]);

  const savedPrefs = record?.status === 'saved' ? record.preferences : null;

  const categories = useMemo(() => {
    const seen = new Map<number, string>();
    for (const item of menu.items) seen.set(item.categoryId, item.categoryName);
    return [...seen.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.id - b.id);
  }, [menu.items]);

  /** itemId → match reasons from the deterministic matcher (positive matches
   *  only — fallback suggestions with score 0 are NOT treated as matches). */
  const matchMap = useMemo(() => {
    if (!savedPrefs) return null;
    const result = recommendItems(savedPrefs, menu.items);
    const map = new Map<number, string[]>();
    if (!result.emptyReason) {
      for (const rec of result.recommendations) {
        if (rec.score > 0) map.set(rec.item.id, rec.reasons);
      }
    }
    return map;
  }, [savedPrefs, menu.items]);

  const visibleItems = useMemo<GuestMenuItem[]>(() => {
    let items = menu.items;
    if (categoryFilter !== 'all') items = items.filter((i) => i.categoryId === categoryFilter);
    if (savedPrefs && onlyMatches && matchMap) items = items.filter((i) => matchMap.has(i.id));
    if (savedPrefs && sortByTaste && matchMap) {
      items = [...items].sort((a, b) => {
        const ma = matchMap.has(a.id) ? 1 : 0;
        const mb = matchMap.has(b.id) ? 1 : 0;
        if (ma !== mb) return mb - ma; // matches first
        return a.id - b.id; // stable for the rest
      });
    }
    return items;
  }, [menu.items, categoryFilter, onlyMatches, sortByTaste, savedPrefs, matchMap]);

  const hiddenByTaste =
    savedPrefs && onlyMatches && matchMap ? menu.items.length - menu.items.filter((i) => matchMap.has(i.id)).length : 0;

  const clearTasteFilters = () => {
    setOnlyMatches(false);
    setSortByTaste(false);
  };

  const openPreferences = () => {
    // Editing from the menu simply routes home where the modal lives,
    // keeping ONE well-designed preference surface (no popup swarm).
    window.location.hash = '';
    setParams({});
  };

  const sawHighlight = () => {
    if (highlightId == null) return;
    const next = new URLSearchParams(params);
    next.delete('item');
    setParams(next, { replace: true });
  };

  return (
    <div className="guest-page">
      <GuestNav onEditPreferences={openPreferences} prefRecord={record} />

      <main className="menu-page-main">
        <header className="menu-header">
          <div>
            <span className="guest-eyebrow">Brew Haven</span>
            <h1 className="menu-title">Our menu</h1>
            <p className="guest-section-sub">
              {menu.source === 'live'
                ? 'Live from our kitchen records.'
                : `As published on ${menu.capturedAt ?? 'our latest update'} — prices in ₹.`}
            </p>
          </div>
          <Link to="/" className="btn btn-outline">
            ← Back home
          </Link>
        </header>

        {savedPrefs && matchMap && (
          <div className="menu-taste-bar" role="group" aria-label="Preference-aware browsing">
            <label className="menu-toggle">
              <input
                type="checkbox"
                checked={sortByTaste}
                onChange={(e) => {
                  setSortByTaste(e.target.checked);
                  if (!e.target.checked) setOnlyMatches(false);
                }}
              />
              <span>Sort by my taste</span>
            </label>
            <label className="menu-toggle">
              <input
                type="checkbox"
                checked={onlyMatches}
                onChange={(e) => setOnlyMatches(e.target.checked)}
                disabled={!sortByTaste}
              />
              <span>Only show matches</span>
            </label>
            {(sortByTaste || onlyMatches) && (
              <button type="button" className="btn btn-ghost menu-taste-clear" onClick={clearTasteFilters}>
                Clear taste filters
              </button>
            )}
            {onlyMatches && hiddenByTaste > 0 && (
              <span className="menu-taste-note" role="status">
                {hiddenByTaste} item{hiddenByTaste === 1 ? '' : 's'} hidden by your taste filter.
              </span>
            )}
            <span className="menu-taste-hint">
              <Link to="/">Adjust preferences</Link>
            </span>
          </div>
        )}

        <div className="menu-chips" role="group" aria-label="Filter by category">
          <button
            type="button"
            className={`menu-chip ${categoryFilter === 'all' ? 'menu-chip-active' : ''}`}
            onClick={() => setCategoryFilter('all')}
          >
            All
          </button>
          {categories.map((c) => (
            <button
              key={c.id}
              type="button"
              className={`menu-chip ${categoryFilter === c.id ? 'menu-chip-active' : ''}`}
              onClick={() => setCategoryFilter(c.id)}
            >
              {c.name}
            </button>
          ))}
        </div>

        <div className="guest-grid menu-grid" ref={gridRef}>
          {visibleItems.map((item) => (
            <MenuItemCard
              key={item.id}
              item={item}
              pairings={pairingsFor(item, menu.items)}
              matchReasons={matchMap?.get(item.id)}
              highlight={highlightId === item.id}
              onHighlightSeen={sawHighlight}
            />
          ))}
        </div>
        {visibleItems.length === 0 && (
          <p className="rec-empty">
            {onlyMatches && matchMap
              ? 'Nothing matches your current taste filters — clear them above or adjust your preferences.'
              : 'Nothing in this section right now — try another category.'}
          </p>
        )}
      </main>

      <footer className="guest-footer">
        <div className="guest-footer-inner">
          <span className="guest-footer-brand">☕ Brew Haven</span>
          <p className="guest-footer-note">
            Ingredient notes reflect what our menu records state — when we cannot verify something,
            we say so. Please confirm allergies and dietary needs with our staff.
          </p>
        </div>
      </footer>
    </div>
  );
}
