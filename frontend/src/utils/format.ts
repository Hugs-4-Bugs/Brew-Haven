export function formatMoney(amount: number | undefined | null): string {
  const value = typeof amount === 'number' && Number.isFinite(amount) ? amount : 0;
  return `₹${value.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;
}

export function formatDate(iso: string | undefined | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

export interface ParsedBillLine {
  name: string;
  category?: string;
  quantity: number;
  price: number;
  total: number;
}

/** Bill.productDetails is a JSON STRING — parse defensively. */
export function parseBillLines(productDetails: string | undefined | null): ParsedBillLine[] {
  if (!productDetails) return [];
  try {
    const arr = JSON.parse(productDetails);
    if (!Array.isArray(arr)) return [];
    return arr.map((line) => ({
      name: String(line?.name ?? ''),
      category: line?.category ? String(line.category) : undefined,
      quantity: Number(line?.quantity ?? 0),
      price: Number(line?.price ?? 0),
      total: Number(line?.total ?? 0),
    }));
  } catch {
    return [];
  }
}

export function statusLabel(status: string | undefined): string {
  return status === 'true' ? 'Available' : 'Unavailable';
}

export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
