package com.inn.cafe.Service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Revenue intelligence — every number is computed from REAL tables
 * (bill / product / payments / campaign / campaign_event / interaction_event).
 * Admin-only (enforced in the impl via JwtFilter.isAdmin()).
 *
 * Honesty rules implemented here:
 *  - "Best sellers" only exist once real bills contain the product.
 *  - Campaign revenue is ONLY attributed from server-verified CONVERTED events.
 *  - Upsell metrics are explicitly labelled client-reported signals.
 *  - Insights carry a sampleSize + confidence and degrade to
 *    "Not enough data yet" below the documented thresholds.
 */
public interface AnalyticsService {

    /** Sales + customer + product overview for the Revenue Intelligence dashboard. */
    ResponseEntity<Map<String, Object>> getOverview();

    /** Customer CRM list with order stats and computed segments. */
    ResponseEntity<Map<String, Object>> getCustomers();

    /** Per-campaign performance (sent/viewed/clicked/converted/revenue). */
    ResponseEntity<Map<String, Object>> getCampaigns();

    /** Upsell measurement (client-reported shown/accepted events). */
    ResponseEntity<Map<String, Object>> getUpsells();

    /** Deterministic, data-grounded insights ("Not enough data yet" when below thresholds). */
    ResponseEntity<Map<String, Object>> getInsights();

    /** Cart funnel (created -> checkout started -> orders completed) + abandonment rate. */
    ResponseEntity<Map<String, Object>> getCartFunnel();
}
