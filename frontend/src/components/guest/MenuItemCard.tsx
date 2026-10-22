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

import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { GuestMenuItem } from '../../guest/menuSnapshot';
import type { PairingSuggestion } from '../../guest/pairings';
import { formatMoney } from '../../utils/format';
import { recordEvent } from '../../api/revenue';

interface MenuItemCardProps {
  item: GuestMenuItem;
  pairings?: PairingSuggestion[];
  matchReasons?: string[];
  highlight?: boolean;
  onHighlightSeen?: () => void;
  /** Cart hook: adds this item (or a pairing, with viaUpsell=true). */
  onAdd?: (item: GuestMenuItem, viaUpsell?: boolean) => void;
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

export default function MenuItemCard({ item, pairings = [], matchReasons, highlight, onHighlightSeen, onAdd }: MenuItemCardProps): JSX.Element {
  const [pairingDismissed, setPairingDismissed] = useState(false);
  const upsellReported = useRef(false);
  const available = item.status === 'true';

  // Upsell measurement (fire-and-forget, signed-in sessions only): the pairing
  // suggestion being VISIBLE is itself the event we measure.
  useEffect(() => {
    if (pairings.length > 0 && !pairingDismissed && !upsellReported.current) {
      upsellReported.current = true;
      void recordEvent('UPSELL_SHOWN', item.id, 'pairing suggestion displayed');
    }
  }, [pairings.length, pairingDismissed, item.id]);
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
            {onAdd && pairings[0] && (
              <button
                type="button"
                className="mic-pairing-add"
                aria-label={`Add pairing ${pairings[0].item.name} to the cart`}
                onClick={() => onAdd(pairings[0].item, true)}
              >
                + Add
              </button>
            )}
            <button type="button" className="mic-pairing-close" aria-label="Dismiss pairing suggestion" onClick={() => setPairingDismissed(true)}>
              ✕
            </button>
          </div>
        )}
        <div className="mic-actions-row">
          {onAdd ? (
            available ? (
              <button type="button" className="btn btn-primary mic-add" onClick={() => onAdd(item)}>
                + Add to cart
              </button>
            ) : (
              <span className="mic-add mic-add-unavailable">Unavailable</span>
            )
          ) : (
            <span />
          )}
          <Link to={`/menu?item=${item.id}`} className="mic-view" aria-label={`View ${item.name} in the full menu`}>
            View in menu →
          </Link>
        </div>
      </div>
    </article>
  );
}
