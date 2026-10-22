package com.inn.cafe.Service;

import com.inn.cafe.Wrapper.PricingResult;

import java.util.List;
import java.util.Map;

/**
 * The single authoritative pricing engine for checkout.
 *
 * Every money figure in the payment flow (checkout display, payment intent,
 * provider order amount, bill total, revenue records) originates HERE —
 * computed from live database product prices. Client-supplied prices, totals
 * or discounts are never accepted anywhere in the flow.
 */
public interface PricingService {

    /**
     * Prices a cart server-side.
     *
     * @param items         list of {productId, quantity} — NO prices accepted
     * @param couponCode    optional campaign/coupon code (validated fully server-side)
     * @param customerEmail optional customer email (for per-customer coupon limits)
     * @return the authoritative breakdown (includes the coupon rejection reason
     *         when a supplied code does not apply)
     * @throws IllegalArgumentException on invalid cart content (unknown product,
     *         unavailable product, bad quantity) — mapped to HTTP 400 by callers
     */
    PricingResult price(List<Map<String, Object>> items, String couponCode, String customerEmail);
}
