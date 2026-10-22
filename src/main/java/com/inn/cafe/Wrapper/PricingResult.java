package com.inn.cafe.Wrapper;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SERVER-AUTHORITATIVE pricing breakdown produced by PricingService.
 *
 * This is the single source of truth used for: checkout display, payment
 * intent creation, provider order amount, bill total and revenue records.
 * Frontend-computed values are NEVER mixed in — the browser may only render
 * what this object contains.
 *
 * Money representation: whole rupees (integers). The pricing engine applies a
 * documented deterministic rounding (HALF_UP on discount and tax) so display,
 * bill and provider amount are always identical. totalPaise = total * 100 is
 * the exact amount sent to the payment provider (smallest currency unit).
 */
@Data
public class PricingResult {

    private int subtotal;
    private int discount;
    private int tax;
    private int total;
    private long totalPaise;
    private String currency = "INR";

    private boolean couponApplied;
    private String couponCode;
    private String couponName;
    private String couponOfferType;   // PERCENT | FLAT
    private Integer couponOfferValue;
    /** Set when a code was supplied but NOT applied (why the coupon was rejected). */
    private String couponRejectionReason;

    private List<ItemLine> items;

    @Data
    @NoArgsConstructor
    public static class ItemLine {
        private Integer productId;
        private String name;
        private String category;
        private int unitPrice;
        private int quantity;
        private int lineTotal;

        public ItemLine(Integer productId, String name, String category, int unitPrice, int quantity, int lineTotal) {
            this.productId = productId;
            this.name = name;
            this.category = category;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
            this.lineTotal = lineTotal;
        }
    }
}
