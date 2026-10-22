import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import FeedbackForm from '../components/guest/FeedbackForm';
import CampaignBanner from '../components/guest/CampaignBanner';
import { AuthProvider } from '../auth/AuthContext';
import * as revenue from '../api/revenue';

/** In-memory fetch mock: tests route by URL. */
function mockFetch(routes: Record<string, { status?: number; body: unknown }>) {
  const calls: { url: string; body?: unknown }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = String(url).replace(/^https?:\/\/[^/]+/, '');
    const match = routes[path];
    calls.push({ url: path, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (!match) return new Response(JSON.stringify({ message: 'not found' }), { status: 404 });
    return new Response(JSON.stringify(match.body ?? {}), { status: match.status ?? 200 });
  }));
  return calls;
}

describe('FeedbackForm — post-order rating', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('requires a star before submitting', async () => {
    const calls = mockFetch({});
    render(<FeedbackForm billUuid="BILL-1" />);
    fireEvent.click(screen.getByRole('button', { name: /send feedback/i }));
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(calls).toHaveLength(0); // nothing sent
  });

  it('submits rating + comment and shows the thank-you state', async () => {
    const calls = mockFetch({ '/feedback/submit': { body: { message: 'Thank you for your feedback!' } } });
    render(<FeedbackForm billUuid="BILL-1" />);
    fireEvent.click(screen.getByRole('radio', { name: '5 stars' }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Loved the dosa' } });
    fireEvent.click(screen.getByRole('button', { name: /send feedback/i }));
    await waitFor(() => expect(screen.getByRole('status')).toBeTruthy());
    expect(calls[0]?.body).toMatchObject({ billUuid: 'BILL-1', rating: 5, comment: 'Loved the dosa' });
  });

  it('renders nothing without a bill uuid', () => {
    const { container } = render(<FeedbackForm billUuid="" />);
    expect(container.firstElementChild).toBeNull();
  });
});

describe('CampaignBanner — honest empty state', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('renders nothing when no active campaigns exist', async () => {
    mockFetch({ '/campaign/active': { body: { campaigns: [] } } });
    const { container } = render(
      <MemoryRouter>
        <AuthProvider>
          <CampaignBanner />
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(container.firstElementChild).toBeNull());
  });

  it('shows the offer with its server code and reports the click', async () => {
    const calls = mockFetch({
      '/campaign/active': { body: { campaigns: [{ id: 2, name: 'Diwali Special', code: 'BREW2', offerType: 'PERCENT', offerValue: 10 }] } },
      '/campaign/event': { body: { message: 'recorded' } },
    });
    const onApply = vi.fn();
    render(
      <MemoryRouter>
        <AuthProvider>
          <CampaignBanner onApply={onApply} />
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText(/Diwali Special/)).toBeTruthy());
    expect(screen.getByText(/10% off/)).toBeTruthy();
    expect(screen.getByText('BREW2')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /apply offer/i }));
    await waitFor(() => expect(onApply).toHaveBeenCalled());
    const clickCall = calls.find((c) => c.url === '/campaign/event' && (c.body as { type?: string } | undefined)?.type === 'CLICKED');
    expect(clickCall).toBeTruthy();
  });
});

describe('revenue API client — message unwrapping', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('placeOrder sends the campaignCode only when provided', async () => {
    const calls = mockFetch({ '/bill/generateReport': { body: { uuid: 'BILL-42' } } });
    const result = await revenue.placeOrder({
      name: 'C', contactNumber: '9', email: 'c@x', paymentMethod: 'Cash',
      productDetails: '[]', totalAmount: 100, isGenerate: true,
    });
    expect(result.uuid).toBe('BILL-42');
    expect(calls[0]?.body).not.toHaveProperty('campaignCode');

    await revenue.placeOrder({
      name: 'C', contactNumber: '9', email: 'c@x', paymentMethod: 'Cash',
      productDetails: '[]', totalAmount: 100, isGenerate: true, campaignCode: 'BREW2',
    });
    expect(calls[1]?.body).toMatchObject({ campaignCode: 'BREW2' });
  });
});
