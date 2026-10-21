/**
 * Guest menu item card.
 *
 * The backend's product model has NO image URLs — rather than fabricating or
 * hot-linking random photos, every card renders an intentional category-themed
 * visual tile (emoji + hue gradient). That is the documented asset strategy.
 *
 * Verified ingredient tags (evidence in the real name/description) render as
 * facts; unverified ones render as "confirm with staff" advisories — the card
 * NEVER claims allergen safety it cannot prove.
 */

import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { GuestMenuItem } from '../../guest/menuSnapshot';
import type { PairingSuggestion } from '../../guest/pairings';
import { formatMoney } from '../../utils/format';

interface MenuItemCardProps {
  item: GuestMenuItem;
  pairings?: PairingSuggestion[];
  matchReasons?: string[];
  highlight?: boolean;
  onHighlightSeen?: () => void;
}

const VERIFIED_TAG_LABELS: Record<string, string> = {
  dairy: 'Contains dairy',
  sweet: 'Sweet',
  spicy: 'Spicy',
  'high-protein': 'High protein',
  nuts: 'Contains nuts',
  eggs: 'Contains egg',
  coffee: 'Coffee',
};

const ADVISORY_TAG_LABELS: Record<string, string> = {
  gluten: 'Gluten — confirm with staff',
};

export default function MenuItemCard({ item, pairings = [], matchReasons, highlight, onHighlightSeen }: MenuItemCardProps): JSX.Element {
  const [pairingDismissed, setPairingDismissed] = useState(false);
  const available = item.status === 'true';
  const verifiedTags = item.tags.filter((t) => t.verified && VERIFIED_TAG_LABELS[t.kind]);
  const advisoryTags = item.tags.filter((t) => !t.verified && ADVISORY_TAG_LABELS[t.kind]);

  return (
    <article
      className={`mic ${highlight ? 'mic-highlight' : ''} ${!available ? 'mic-unavailable' : ''}`}
      data-item-id={item.id}
      onAnimationEnd={onHighlightSeen}
    >
      <div className="mic-media" style={{ '--hue': String(item.visual.hue) } as React.CSSProperties}>
        <span className="mic-emoji" role="img" aria-label={`${item.name} illustration`}>
          {item.visual.emoji}
        </span>
        {!available && <span className="mic-unavailable-badge">Currently unavailable</span>}
      </div>
      <div className="mic-body">
        <div className="mic-head">
          <h3 className="mic-name">{item.name}</h3>
          <span className="mic-price">{formatMoney(item.price)}</span>
        </div>
        <span className="mic-category">{item.categoryName}</span>
        {item.description && <p className="mic-desc">{item.description}</p>}
        {(verifiedTags.length > 0 || advisoryTags.length > 0) && (
          <ul className="mic-tags" aria-label="Ingredient information">
            {verifiedTags.map((t) => (
              <li key={t.kind} className="mic-tag mic-tag-verified">{VERIFIED_TAG_LABELS[t.kind]}</li>
            ))}
            {advisoryTags.map((t) => (
              <li key={t.kind} className="mic-tag mic-tag-advisory">{ADVISORY_TAG_LABELS[t.kind]}</li>
            ))}
          </ul>
        )}
        {matchReasons && matchReasons.length > 0 && (
          <p className="mic-match">
            <span aria-hidden="true">✨</span> Why this pick: {matchReasons.slice(0, 3).join(' · ')}
          </p>
        )}
        {pairings.length > 0 && !pairingDismissed && (
          <div className="mic-pairing">
            <span>
              Pairs well with{' '}
              {pairings.map((p, i) => (
                <span key={p.item.id}>
                  {i > 0 && ' / '}
                  <strong>{p.item.name}</strong> <span className="mic-pairing-price">{formatMoney(p.item.price)}</span>
                </span>
              ))}
            </span>
            <button type="button" className="mic-pairing-close" aria-label="Dismiss pairing suggestion" onClick={() => setPairingDismissed(true)}>
              ✕
            </button>
          </div>
        )}
        <Link to={`/menu?item=${item.id}`} className="mic-view" aria-label={`View ${item.name} in the full menu`}>
          View in menu →
        </Link>
      </div>
    </article>
  );
}
