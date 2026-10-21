import { describe, expect, it } from 'vitest';
import { formatMoney, parseBillLines, statusLabel } from '../utils/format';

describe('formatMoney', () => {
  it('formats rupees with Indian grouping', () => {
    expect(formatMoney(1234)).toBe('₹1,234');
    expect(formatMoney(1234567)).toBe('₹12,34,567');
  });

  it('handles decimals, null and undefined safely', () => {
    expect(formatMoney(99.5)).toBe('₹99.5');
    expect(formatMoney(null)).toBe('₹0');
    expect(formatMoney(undefined)).toBe('₹0');
    expect(formatMoney(Number.NaN)).toBe('₹0');
  });
});

describe('parseBillLines — backend stores productDetails as a JSON STRING', () => {
  it('parses valid line arrays', () => {
    const lines = parseBillLines(
      JSON.stringify([
        { id: 1, name: 'Pizza', category: 'Pizza', quantity: 2, price: 199, total: 398 },
      ]),
    );
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ name: 'Pizza', quantity: 2, price: 199, total: 398 });
  });

  it('returns [] for null/undefined/invalid JSON', () => {
    expect(parseBillLines(null)).toEqual([]);
    expect(parseBillLines(undefined)).toEqual([]);
    expect(parseBillLines('not json')).toEqual([]);
    expect(parseBillLines('{"not":"an array"}')).toEqual([]);
  });
});

describe('statusLabel — DB contract is "true"/"false" strings', () => {
  it('maps status strings to human labels', () => {
    expect(statusLabel('true')).toBe('Available');
    expect(statusLabel('false')).toBe('Unavailable');
    expect(statusLabel(undefined)).toBe('Unavailable');
  });
});
