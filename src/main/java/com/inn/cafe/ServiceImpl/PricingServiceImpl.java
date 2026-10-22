package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.POJO.Campaign;
import com.inn.cafe.POJO.CampaignEvent;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.Service.PricingService;
import com.inn.cafe.Wrapper.PricingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-authoritative pricing engine.
 *
 * Flow: cart items -> validate products/prices from the DB -> subtotal ->
 * validate coupon (campaign) -> discount -> tax -> final payable amount.
 *
 * Money handling: all arithmetic in BigDecimal with a DOCUMENTED deterministic
 * rounding rule — the discount and the tax are each rounded HALF_UP to whole
 * rupees — so the displayed amount, the bill total and the provider amount
 * (total * 100 paise) are always exactly the same number. No floating point.
 */
@Slf4j
@Service
public class PricingServiceImpl implements PricingService {

    private static final int MAX_DISTINCT_ITEMS = 50;
    private static final int MAX_QUANTITY_PER_ITEM = 99;

    @Autowired
    private ProductDao productDao;

    @Autowired
    private CampaignDao campaignDao;

    @Autowired
    private CampaignEventDao campaignEventDao;

    /** Optional tax/fee percent (e.g. 5 for 5%); default 0 = tax-inclusive menu prices. */
    @Value("${cafe.payment.tax-percent:0}")
    private int taxPercent;

    @Override
    @Transactional
    public PricingResult price(List<Map<String, Object>> items, String couponCode, String customerEmail) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty.");
        }
        if (taxPercent < 0 || taxPercent > 100) {
            throw new IllegalArgumentException("Invalid tax configuration.");
        }

        // ---- merge quantities per product id (duplicate lines collapse)
        Map<Integer, Integer> quantities = new LinkedHashMap<>();
        for (Map<String, Object> raw : items) {
            Integer productId = parseInt(String.valueOf(raw.get("productId")));
            Integer quantity = parseInt(String.valueOf(raw.get("quantity")));
            if (productId == null || productId <= 0) {
                throw new IllegalArgumentException("Invalid product id in cart.");
            }
            if (quantity == null || quantity <= 0 || quantity > MAX_QUANTITY_PER_ITEM) {
                throw new IllegalArgumentException(
                        "Quantity for product " + productId + " must be between 1 and " + MAX_QUANTITY_PER_ITEM + ".");
            }
            quantities.merge(productId, quantity, Integer::sum);
        }
        if (quantities.size() > MAX_DISTINCT_ITEMS) {
            throw new IllegalArgumentException("Too many distinct items (max " + MAX_DISTINCT_ITEMS + ").");
        }

        // ---- price every line from the LIVE database (client prices are ignored)
        List<PricingResult.ItemLine> lines = new ArrayList<>();
        int subtotal = 0;
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            Product product = productDao.findById(entry.getKey()).orElse(null);
            if (product == null) {
                throw new IllegalArgumentException("Product " + entry.getKey() + " does not exist.");
            }
            if (!"true".equalsIgnoreCase(String.valueOf(product.getStatus()))) {
                throw new IllegalArgumentException(product.getName() + " is currently unavailable.");
            }
            Integer price = product.getPrice();
            if (price == null || price < 0) {
                throw new IllegalArgumentException(product.getName() + " has no valid price.");
            }
            int qty = Math.min(entry.getValue(), MAX_QUANTITY_PER_ITEM);
            int lineTotal = price * qty;
            subtotal += lineTotal;
            String categoryName = "";
            if (product.getCategory() != null && product.getCategory().getName() != null) {
                categoryName = product.getCategory().getName();
            }
            lines.add(new PricingResult.ItemLine(product.getId(), product.getName(), categoryName, price, qty, lineTotal));
        }

        PricingResult result = new PricingResult();
        result.setItems(lines);
        result.setSubtotal(subtotal);

        // ---- coupon validation + discount — 100% server-side
        String code = couponCode == null ? "" : couponCode.trim();
        if (!code.isEmpty()) {
            String rejection = applyCoupon(result, code, subtotal, customerEmail);
            result.setCouponRejectionReason(rejection);
            result.setCouponApplied(rejection == null);
            if (rejection == null) {
                result.setCouponCode(code);
            }
        } else {
            result.setCouponApplied(false);
        }

        // ---- tax/fees on the discounted base, HALF_UP to whole rupees
        int discount = result.getDiscount();
        int tax = 0;
        if (taxPercent > 0) {
            tax = BigDecimal.valueOf(subtotal - discount)
                    .multiply(BigDecimal.valueOf(taxPercent))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .intValueExact();
        }
        result.setTax(tax);

        int total = Math.max(subtotal - discount + tax, 0);
        result.setTotal(total);
        result.setTotalPaise(total * 100L);
        return result;
    }

    /**
     * Validates the coupon against every business rule and, when applicable,
     * sets result.discount. Returns null on success or a human-readable
     * rejection reason.
     */
    private String applyCoupon(PricingResult result, String code, int subtotal, String customerEmail) {
        Optional<Campaign> opt = campaignDao.findByCodeIgnoreCase(code);
        if (!opt.isPresent()) {
            return "Coupon code not found.";
        }
        Campaign c = opt.get();
        if (!Campaign.STATUS_ACTIVE.equals(c.getStatus())) {
            return "This coupon is not active.";
        }
        String today = LocalDate.now().toString();
        if (c.getStartsAt() != null && !c.getStartsAt().isEmpty() && today.compareTo(c.getStartsAt()) < 0) {
            return "This coupon is not active yet.";
        }
        if (c.getEndsAt() != null && !c.getEndsAt().isEmpty() && today.compareTo(c.getEndsAt()) > 0) {
            return "This coupon has expired.";
        }
        long conversions = campaignEventDao.countByCampaignIdAndEventType(c.getId(), CampaignEvent.TYPE_CONVERTED);
        if (c.getUsageLimit() != null && conversions >= c.getUsageLimit()) {
            return "This coupon has reached its usage limit.";
        }
        if (c.getMaxUsesPerCustomer() != null && customerEmail != null && !customerEmail.isBlank()) {
            long used = campaignEventDao.countByCampaignIdAndEventTypeAndEmail(
                    c.getId(), CampaignEvent.TYPE_CONVERTED, customerEmail.trim().toLowerCase());
            if (used >= c.getMaxUsesPerCustomer()) {
                return "You have already used this coupon.";
            }
        }
        Integer offerValue = c.getOfferValue();
        boolean percent = "PERCENT".equals(c.getOfferType());
        boolean flat = "FLAT".equals(c.getOfferType());
        if (offerValue == null || offerValue < 0 || (!percent && !flat)) {
            return "This code has no discount offer attached.";
        }
        if (c.getMinOrderValue() != null && subtotal < c.getMinOrderValue()) {
            return "Minimum order of Rs." + c.getMinOrderValue() + " required for this coupon.";
        }

        int discount;
        if (percent) {
            discount = BigDecimal.valueOf(subtotal)
                    .multiply(BigDecimal.valueOf(offerValue))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .intValueExact();
        } else {
            discount = offerValue;
        }
        if (c.getMaxDiscount() != null && discount > c.getMaxDiscount()) {
            discount = c.getMaxDiscount();
        }
        if (discount > subtotal) {
            discount = subtotal;
        }
        if (discount <= 0) {
            return "This coupon does not reduce your total.";
        }

        result.setDiscount(discount);
        result.setCouponName(c.getName());
        result.setCouponOfferType(c.getOfferType());
        result.setCouponOfferValue(offerValue);
        return null;
    }

    private static Integer parseInt(String s) {
        if (s == null || s.trim().isEmpty() || "null".equals(s)) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
