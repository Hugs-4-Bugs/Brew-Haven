/**
 * Signature dish hero carousel.
 *
 *  - Auto-advances every 5.5s, PAUSES on hover / keyboard focus, and never
 *    auto-advances under prefers-reduced-motion (utils/motion.ts helper).
 *  - Manual previous/next buttons + dot indicators (aria-current).
 *  - Content is REAL menu data (name, description, price, category) resolved
 *    from guest/featured.ts config — unavailable items are filtered upstream.
 *  - Fixed aspect-ratio media tile (no layout shift), CSS-only transitions
 *    with a reduced-motion media query, keyboard accessible.
 */

import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { FeaturedItem } from '../../guest/featured';
import { prefersReducedMotion } from '../../utils/motion';
import { formatMoney } from '../../utils/format';

const AUTO_ADVANCE_MS = 5500;

interface SignatureCarouselProps {
  featured: FeaturedItem[];
}

export default function SignatureCarousel({ featured }: SignatureCarouselProps): JSX.Element | null {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const reducedRef = useRef(false);

  useEffect(() => {
    reducedRef.current = prefersReducedMotion();
    const mq = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    const onChange = () => {
      reducedRef.current = prefersReducedMotion();
    };
    mq?.addEventListener?.('change', onChange);
    return () => mq?.removeEventListener?.('change', onChange);
  }, []);

  const count = featured.length;

  // Auto-advance — disabled when paused, reduced-motion, or a single slide.
  useEffect(() => {
    if (paused || count < 2 || reducedRef.current) return;
    const timer = window.setInterval(() => {
      setIndex((i) => (i + 1) % count);
    }, AUTO_ADVANCE_MS);
    return () => window.clearInterval(timer);
  }, [paused, count]);

  // Keep index valid if the featured list shrinks.
  useEffect(() => {
    if (index >= count) setIndex(0);
  }, [count, index]);

  if (count === 0) return null;

  const go = (next: number) => setIndex(((next % count) + count) % count);

  return (
    <section
      className="sc-root"
      aria-roledescription="carousel"
      aria-label="Signature dishes"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
      aria-live="polite"
    >
      <div className="sc-stage" aria-hidden={false}>
        {featured.map((f, i) => (
          <article
            key={f.item.id}
            className={`sc-slide ${i === index ? 'sc-slide-active' : ''}`}
            aria-hidden={i !== index}
            aria-label={`${i + 1} of ${count}: ${f.item.name}`}
          >
            <div className="sc-media" style={{ '--hue': String(f.item.visual.hue) } as React.CSSProperties}>
              <span className="sc-emoji" role="img" aria-label={`${f.item.name} illustration`}>
                {f.item.visual.emoji}
              </span>
              <span className="sc-badge">Signature</span>
            </div>
            <div className="sc-info">
              <div className="sc-meta">
                <span className="sc-category">{f.item.categoryName}</span>
                <span className="sc-price">{formatMoney(f.item.price)}</span>
              </div>
              <h3 className="sc-name">{f.item.name}</h3>
              <p className="sc-tagline">{f.tagline}</p>
              <p className="sc-desc">{f.item.description}</p>
              <div className="sc-actions">
                <Link className="btn btn-primary" to={`/menu?item=${f.item.id}`}>
                  View item
                </Link>
                <Link className="btn btn-outline" to="/menu">
                  Explore menu
                </Link>
              </div>
            </div>
          </article>
        ))}
      </div>

      {count > 1 && (
        <div className="sc-controls">
          <button
            type="button"
            className="sc-arrow"
            aria-label="Previous signature dish"
            onClick={() => go(index - 1)}
          >
            ‹
          </button>
          <div className="sc-dots" role="tablist" aria-label="Choose signature dish">
            {featured.map((f, i) => (
              <button
                key={f.item.id}
                type="button"
                role="tab"
                className={`sc-dot ${i === index ? 'sc-dot-active' : ''}`}
                aria-label={`Show ${f.item.name}`}
                aria-current={i === index ? 'true' : undefined}
                onClick={() => go(i)}
              />
            ))}
          </div>
          <button
            type="button"
            className="sc-arrow"
            aria-label="Next signature dish"
            onClick={() => go(index + 1)}
          >
            ›
          </button>
        </div>
      )}
    </section>
  );
}
