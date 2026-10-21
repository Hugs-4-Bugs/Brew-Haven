import { describe, expect, it } from 'vitest';
import { prefersReducedMotion } from '../utils/motion';
import { computeRevenueByDayFixture } from './fixtures';

describe('motion utilities — functionality over effects', () => {
  it('detects reduced-motion preference without crashing', () => {
    // jsdom supports matchMedia only if defined; our helper guards against absence
    expect(typeof prefersReducedMotion()).toBe('boolean');
  });
});

describe('dashboard analytics (client-computed from real bill data)', () => {
  it('buckets revenue by day using the BILL-<epoch> uuid contract', () => {
    const now = Date.now();
    const days = computeRevenueByDayFixture([
      { uuid: `BILL-${now}`, total: 250 },                 // today
      { uuid: `BILL-${now - 86400000}`, total: 100 },      // yesterday
      { uuid: `BILL-${now - 3 * 86400000}`, total: 40 },   // 3 days ago
      { uuid: 'not-a-bill-uuid', total: 999 },             // undated -> excluded
    ]);
    expect(days).toHaveLength(7);
    const today = days[6];
    expect(today.revenue).toBe(250);
    expect(today.bills).toBe(1);
    expect(days[5].revenue).toBe(100);
    expect(days[3].revenue).toBe(40);
    // the undated bill must not be counted anywhere
    const totalAll = days.reduce((s, d) => s + d.revenue, 0);
    expect(totalAll).toBe(390);
  });
});
