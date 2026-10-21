import gsap from 'gsap';

/**
 * Motion utilities — ALL animation is centralized here so that
 * prefers-reduced-motion is honoured in exactly one place.
 * Functionality always has priority over effects: reduced-motion users get
 * instant state changes (duration 0), never lost information.
 */
export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Fade-and-rise entrance for a set of elements (staggered). No-op under reduced motion. */
export function animateIn(selector: string, opts: { stagger?: number; y?: number } = {}): void {
  if (prefersReducedMotion()) return;
  const targets = document.querySelectorAll(selector);
  if (!targets.length) return;
  gsap.fromTo(
    targets,
    { opacity: 0, y: opts.y ?? 14 },
    { opacity: 1, y: 0, duration: 0.45, ease: 'power2.out', stagger: opts.stagger ?? 0.06, clearProps: 'all' },
  );
}

/** Card-hover 3D tilt (subtle depth). Disabled for reduced motion + touch devices. */
export function attachTilt(el: HTMLElement): () => void {
  if (prefersReducedMotion() || !window.matchMedia('(hover: hover)').matches) return () => {};
  const strength = 4;
  const onMove = (e: MouseEvent) => {
    const rect = el.getBoundingClientRect();
    const px = (e.clientX - rect.left) / rect.width - 0.5;
    const py = (e.clientY - rect.top) / rect.height - 0.5;
    gsap.to(el, {
      rotateY: px * strength,
      rotateX: -py * strength,
      transformPerspective: 700,
      duration: 0.25,
      ease: 'power2.out',
    });
  };
  const onLeave = () => gsap.to(el, { rotateX: 0, rotateY: 0, duration: 0.4, ease: 'power2.out' });
  el.addEventListener('mousemove', onMove);
  el.addEventListener('mouseleave', onLeave);
  return () => {
    el.removeEventListener('mousemove', onMove);
    el.removeEventListener('mouseleave', onLeave);
    gsap.killTweensOf(el);
  };
}

/** Number counter roll-up for stat values. Reduced motion => instant set. */
export function countUp(el: HTMLElement, target: number, suffix = ''): void {
  if (prefersReducedMotion() || target === 0) {
    el.textContent = `${target.toLocaleString()}${suffix}`;
    return;
  }
  const obj = { v: 0 };
  gsap.to(obj, {
    v: target,
    duration: 0.8,
    ease: 'power2.out',
    onUpdate: () => {
      el.textContent = `${Math.round(obj.v).toLocaleString()}${suffix}`;
    },
  });
}
