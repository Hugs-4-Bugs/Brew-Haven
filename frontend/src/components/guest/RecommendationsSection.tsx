/**
 * Personalized recommendations section.
 *
 *  - Renders only when the guest has SAVED preferences (skipped guests see
 *    the regular homepage instead — no nagging).
 *  - Every card is a REAL menu item; the badge states honestly whether the
 *    explanation came from the rule-based matcher ("Smart match") or an AI
 *    service ("AI-assisted") — see guest/recommendations.ts contracts.
 *  - Health-note and dietary advisories render as prominent, plain-language
 *    notes. No medical claims, ever.
 *  - "Show more" expands the list; "Edit preferences" reopens the modal;
 *    everything links back into the real menu.
 */

import { useEffect, useState } from 'react';
import { getRecommendations, type RecommendationResult } from '../../guest/recommendations';
import type { GuestMenuItem } from '../../guest/menuSnapshot';
import type { GuestPreferences } from '../../guest/types';
import MenuItemCard from './MenuItemCard';

interface RecommendationsSectionProps {
  preferences: GuestPreferences;
  items: GuestMenuItem[];
  onEditPreferences: () => void;
}

const INITIAL_COUNT = 3;

export default function RecommendationsSection({ preferences, items, onEditPreferences }: RecommendationsSectionProps): JSX.Element | null {
  const [result, setResult] = useState<RecommendationResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getRecommendations(preferences, items)
      .then((r) => {
        if (!cancelled) {
          setResult(r);
          setLoading(false);
        }
      })
      .catch(() => {
        // The matcher is deterministic and never throws; this is pure defence.
        if (!cancelled) {
          setResult({
            recommendations: [],
            advisories: ['Recommendations are temporarily unavailable — the full menu is right below.'],
            excludedCount: 0,
            source: 'rules',
          });
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [preferences, items]);

  if (loading) {
    return (
      <section className="guest-section" aria-busy="true">
        <div className="rec-skeleton" aria-label="Preparing your picks">
          <span className="rec-skeleton-title" />
          <span className="rec-skeleton-line" />
          <span className="rec-skeleton-line rec-skeleton-line-short" />
        </div>
      </section>
    );
  }
  if (!result) return null;

  const visible = showAll ? result.recommendations : result.recommendations.slice(0, INITIAL_COUNT);
  const sourceLabel = result.source === 'ai' ? 'AI-assisted picks' : 'Smart matches';

  return (
    <section className="guest-section guest-section-recs" aria-labelledby="recs-title">
      <div className="guest-section-head">
        <div>
          <span className="guest-eyebrow">Picked for you</span>
          <h2 id="recs-title" className="guest-section-title">
            Your personal menu matches
          </h2>
          <p className="guest-section-sub">
            Matched from your saved preferences against our real menu — curated by {sourceLabel.toLowerCase()}.
          </p>
        </div>
        <div className="guest-section-actions">
          <span className={`rec-badge ${result.source === 'ai' ? 'rec-badge-ai' : ''}`} title={result.source === 'ai' ? 'Explanations assisted by an AI service, grounded in real menu items' : 'Deterministic preference matching over real menu data'}>
            {result.source === 'ai' ? '✦ AI-assisted' : '⚙ Smart match'}
          </span>
          <button type="button" className="btn btn-outline" onClick={onEditPreferences}>
            Edit preferences
          </button>
        </div>
      </div>

      {result.aiNote && <p className="rec-ai-note" role="status">{result.aiNote}</p>}

      {result.advisories.length > 0 && (
        <ul className="rec-advisories" aria-label="Important notes about your preferences">
          {result.advisories.map((a) => (
            <li key={a} className="rec-advisory">
              <span aria-hidden="true">ℹ️</span> {a}
            </li>
          ))}
        </ul>
      )}

      {visible.length === 0 ? (
        <p className="rec-empty">
          No recommendations right now — but the full menu is waiting below. 🍽️
        </p>
      ) : (
        <>
          <div className="guest-grid">
            {visible.map((r) => (
              <MenuItemCard key={r.item.id} item={r.item} matchReasons={r.reasons} />
            ))}
          </div>
          {result.recommendations.length > INITIAL_COUNT && (
            <div className="guest-section-more">
              <button type="button" className="btn btn-outline" onClick={() => setShowAll((v) => !v)}>
                {showAll ? 'Show fewer suggestions' : `Show more suggestions (${result.recommendations.length - INITIAL_COUNT} more)`}
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
