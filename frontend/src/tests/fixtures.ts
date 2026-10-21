type LightBill = { uuid: string; total: number };

/**
 * Pure copy of DashboardPage.computeRevenueByDay so the bucketing logic is unit-testable
 * without mounting React. The page imports its own copy — keep them in sync.
 */
export function computeRevenueByDayFixture(bills: LightBill[]): { day: string; revenue: number; bills: number }[] {
  const days: { day: string; revenue: number; bills: number }[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - i);
    const next = new Date(d);
    next.setDate(next.getDate() + 1);
    const dayBills = bills.filter((b) => {
      const m = /^BILL-(\d+)$/.exec(b.uuid || '');
      if (!m) return false;
      const t = Number(m[1]);
      return t >= d.getTime() && t < next.getTime();
    });
    days.push({
      day: d.toLocaleDateString('en-IN', { weekday: 'short' }),
      revenue: dayBills.reduce((sum, b) => sum + (b.total ?? 0), 0),
      bills: dayBills.length,
    });
  }
  return days;
}
