/**
 * Revenue-platform API layer — customer ordering, preferences sync,
 * campaigns, feedback, events and the admin intelligence endpoints.
 *
 * All endpoints are ADDITIVE backend routes (see backend Rest interfaces).
 * The client attaches the JWT automatically (api/client).
 */

import { request, requestMessage } from './client';
import type { Bill } from './cafe';

// ------------------------------------------------------------------ my profile

export interface MyProfile {
  name: string;
  email: string;
  contactNumber: string;
  role: string;
}

export async function getMyProfile(): Promise<MyProfile> {
  return request<MyProfile>('/user/me');
}

export async function getMyBills(): Promise<Bill[]> {
  return request<Bill[]>('/bill/myBills');
}

/** Places a REAL order through the existing billing API (JWT-protected). */
export async function placeOrder(payload: {
  name: string;
  contactNumber: string;
  email: string;
  paymentMethod: string;
  productDetails: string;
  totalAmount: number;
  isGenerate: boolean;
  campaignCode?: string;
}): Promise<{ uuid: string }> {
  return request<{ uuid: string }>('/bill/generateReport', { method: 'POST', body: payload });
}

// ------------------------------------------------------------------ preferences

export async function getServerPreferences(): Promise<{ preferences: unknown; version?: number } | null> {
  try {
    const raw = await request<{ preferences: unknown; version?: number } | string>('/customer/preferences');
    if (raw && typeof raw === 'object' && raw.preferences) {
      return raw as { preferences: unknown; version?: number };
    }
    return null;
  } catch {
    return null; // not signed in / not stored — never blocks the guest UI
  }
}

export async function saveServerPreferences(payload: string, version: number): Promise<void> {
  try {
    await requestMessage('/customer/preferences', { method: 'POST', body: { payload, version } });
  } catch {
    /* server sync is best-effort — local record remains the fallback */
  }
}

// ------------------------------------------------------------------ campaigns

export interface ActiveCampaign {
  id: number;
  name: string;
  code: string;
  offerType: string;
  offerValue: number | null;
}

export async function getActiveCampaigns(): Promise<ActiveCampaign[]> {
  try {
    const raw = await request<{ campaigns: ActiveCampaign[] }>('/campaign/active');
    return raw?.campaigns ?? [];
  } catch {
    return []; // anonymous / network failure — banner simply hides
  }
}

export async function reportCampaignInteraction(campaignId: number, type: 'VIEWED' | 'CLICKED'): Promise<void> {
  try {
    await requestMessage('/campaign/event', { method: 'POST', body: { campaignId, type } });
  } catch {
    /* measurement only — never blocks browsing */
  }
}

// ------------------------------------------------------------------ events

export type InteractionEventType =
  | 'UPSELL_SHOWN' | 'UPSELL_ACCEPTED' | 'PREF_SAVED'
  | 'CART_CREATED' | 'CHECKOUT_STARTED' | 'ORDER_COMPLETED';

/** Records a guest-experience event. Fire-and-forget: returns silently when
 * the caller is not signed in (the backend requires an account). */
export async function recordEvent(type: InteractionEventType, productId?: number, detail?: string): Promise<void> {
  const body: Record<string, unknown> = { type };
  if (productId != null) body.productId = String(productId);
  if (detail) body.detail = detail;
  try {
    await requestMessage('/events/record', { method: 'POST', body });
  } catch {
    /* rate limited / anonymous — measurement must never break UX */
  }
}

// ------------------------------------------------------------------ feedback

export async function submitFeedback(billUuid: string, rating: number, comment?: string): Promise<string> {
  return requestMessage('/feedback/submit', {
    method: 'POST',
    body: { billUuid, rating, comment: comment ?? '' },
  });
}

// ------------------------------------------------------------------ automation

export async function triggerThankYou(billUuid: string): Promise<void> {
  try {
    await requestMessage('/automation/thank-you', { method: 'POST', body: { billUuid } });
  } catch {
    /* audit-side effect only */
  }
}

// ------------------------------------------------------------------ admin: campaigns

export interface AdminCampaign {
  id: number;
  name: string;
  type: string;
  segment: string;
  offerType: string;
  offerValue: number | null;
  code: string;
  startsAt: string;
  endsAt: string;
  status: string;
  usageLimit: number | null;
  minOrderValue?: number | null;
  maxDiscount?: number | null;
  maxUsesPerCustomer?: number | null;
  createdBy: string;
}

export interface CampaignPerformance extends AdminCampaign {
  sent: number;
  viewed: number;
  clicked: number;
  converted: number;
  revenue: number;
  conversionRate: number;
}

export async function getAdminCampaigns(): Promise<{ campaigns: AdminCampaign[]; channel: string; channelConfigured: boolean }> {
  return request('/campaign/all');
}

export async function createCampaign(payload: {
  name: string;
  segment: string;
  offerType: string;
  offerValue?: number;
  startsAt?: string;
  endsAt?: string;
  usageLimit?: number;
  minOrderValue?: number;
  maxDiscount?: number;
  maxUsesPerCustomer?: number;
}): Promise<string> {
  return requestMessage('/campaign/create', { method: 'POST', body: payload });
}

export async function setCampaignStatus(id: number, status: string): Promise<string> {
  return requestMessage('/campaign/status', { method: 'POST', body: { id: String(id), status } });
}

export async function sendCampaign(id: number): Promise<{ sent: number; failed: number; channel: string; note?: string }> {
  return request('/campaign/send', { method: 'POST', body: { id: String(id) } });
}

export async function getCampaignAnalytics(): Promise<{ campaigns: CampaignPerformance[]; attributionNote: string }> {
  return request('/analytics/campaigns');
}

// ------------------------------------------------------------------ admin: analytics

export interface AnalyticsOverview {
  sales: {
    totalRevenue: number; orderCount: number; avgOrderValue: number;
    revenueLast7Days: number; revenuePrior7Days: number;
  };
  customers: {
    customerCount: number; newCustomersLast7Days: number; repeatCustomers: number;
    repeatRate: number; highValueCustomers: number;
  };
  products: {
    bestSellersByQuantity: { name: string; value: number }[];
    bestSellersByRevenue: { name: string; value: number }[];
    slowMovers: string[];
    categoryPerformance: { name: string; value: number }[];
    note: string;
  };
}

export interface CustomerRow {
  email: string; name: string; orders: number; totalSpent: number;
  avgOrderValue: number; firstOrderAt: string | null; lastOrderAt: string | null;
  segments: string[];
}

export interface Insight {
  type: string; title: string; body: string;
  sampleSize: number; confidence: string; source: string; enoughData: boolean;
}

export async function getAnalyticsOverview(): Promise<AnalyticsOverview> {
  return request('/analytics/overview');
}

export async function getAnalyticsCustomers(): Promise<{ customers: CustomerRow[]; segmentCounts: Record<string, number>; highValueThreshold: number; note: string }> {
  return request('/analytics/customers');
}

export async function getAnalyticsUpsells(): Promise<{
  upsells: { productId: number; productName: string; shown: number; accepted: number; acceptRate: number }[];
  totalShown: number; totalAccepted: number; acceptRate: number; note: string;
}> {
  return request('/analytics/upsells');
}

export async function getAnalyticsInsights(): Promise<{ insights: Insight[]; engine: string; note: string }> {
  return request('/analytics/insights');
}

export async function getCartFunnel(): Promise<{ cartCreated: number; checkoutStarted: number; orderCompleted: number; abandonmentRate: number; note: string }> {
  return request('/analytics/cart-funnel');
}

export async function getFeedbackAll(): Promise<{ feedback: { billUuid: string; email: string; rating: number; comment: string }[]; count: number; averageRating: number }> {
  return request('/feedback/all');
}

// ------------------------------------------------------------------ admin: automation

export interface AutomationRunRow {
  id: number; rule: string; trigger: string; status: string;
  detectedCount: number; actionCount: number; channel: string;
  detail: string; createdBy: string; createdAt: string;
}

export async function getAutomationRuns(): Promise<{ runs: AutomationRunRow[] }> {
  return request('/automation/runs');
}

export async function getAutomationStatus(): Promise<{ channels: { id: string; configured: boolean }[]; scheduledEnabled: boolean; rules: string[]; note: string }> {
  return request('/automation/status');
}

export async function runAutomationRule(rule: string): Promise<string> {
  return requestMessage('/automation/run', { method: 'POST', body: { rule } });
}

// ------------------------------------------------------------------ admin: featured

export async function setProductFeatured(id: number, featured: boolean): Promise<string> {
  return requestMessage('/product/setFeatured', { method: 'POST', body: { id: String(id), featured: featured ? 'true' : 'false' } });
}
