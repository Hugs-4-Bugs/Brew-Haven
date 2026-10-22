package com.inn.cafe.Service;

import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.POJO.Campaign;
import com.inn.cafe.POJO.Category;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.ServiceImpl.PricingServiceImpl;
import com.inn.cafe.Wrapper.PricingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * PricingServiceTest — the authoritative pricing engine (server-side amounts).
 *
 * Covers: DB-price subtotal, percent/flat coupons, caps, minimum order, expiry
 * windows, usage limits, per-customer limits, product validation, quantity
 * bounds, merged lines and tax — the full pricing correctness contract.
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @InjectMocks
    private PricingServiceImpl pricingService;

    @Mock
    private ProductDao productDao;

    @Mock
    private CampaignDao campaignDao;

    @Mock
    private CampaignEventDao campaignEventDao;

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(pricingService, "taxPercent", 0);
    }

    // ---------------------------------------------------------------- fixtures

    private static Product product(int id, String name, int price) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(price);
        p.setStatus("true");
        Category c = new Category();
        c.setId(1);
        c.setName("Beverages");
        p.setCategory(c);
        return p;
    }

    private static Campaign campaign(String code, String offerType, Integer offerValue, String status) {
        Campaign c = new Campaign();
        c.setId(3);
        c.setName("Test offer");
        c.setCode(code);
        c.setOfferType(offerType);
        c.setOfferValue(offerValue);
        c.setStatus(status);
        c.setStartsAt("");
        c.setEndsAt("");
        return c;
    }

    private static Map<String, Object> item(int productId, int quantity) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", productId);
        m.put("quantity", quantity);
        return m;
    }

    private static List<Map<String, Object>> cart(Map<String, Object>... items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> i : items) {
            list.add(i);
        }
        return list;
    }

    // ---------------------------------------------------------------- subtotal from DB prices

    @Test
    void subtotalAlwaysComesFromDatabasePrices() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Cold Coffee", 120)));
        lenient().when(productDao.findById(2)).thenReturn(Optional.of(product(2, "Dosa", 90)));

        PricingResult r = pricingService.price(cart(item(1, 2), item(2, 1)), null, null);
        assertEquals(120 * 2 + 90, r.getSubtotal());
        assertEquals(330, r.getTotal());
        assertEquals(33000L, r.getTotalPaise());
        assertEquals(2, r.getItems().size());
        assertEquals(120, r.getItems().get(0).getUnitPrice());
    }

    @Test
    void duplicateLinesMergeQuantities() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Cold Coffee", 120)));
        PricingResult r = pricingService.price(cart(item(1, 2), item(1, 3)), null, null);
        assertEquals(1, r.getItems().size());
        assertEquals(5, r.getItems().get(0).getQuantity());
        assertEquals(600, r.getSubtotal());
    }

    @Test
    void unknownProductIsRejected() {
        when(productDao.findById(99)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> pricingService.price(cart(item(99, 1)), null, null));
    }

    @Test
    void unavailableProductIsRejected() {
        Product p = product(7, "Mocha", 150);
        p.setStatus("false");
        when(productDao.findById(7)).thenReturn(Optional.of(p));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> pricingService.price(cart(item(7, 1)), null, null));
        assertTrue(ex.getMessage().contains("unavailable"));
    }

    @Test
    void quantityBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> pricingService.price(cart(item(1, 0)), null, null));
        assertThrows(IllegalArgumentException.class, () -> pricingService.price(cart(item(1, 100)), null, null));
        assertThrows(IllegalArgumentException.class, () -> pricingService.price(cart(item(1, -1)), null, null));
    }

    @Test
    void emptyCartIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> pricingService.price(new ArrayList<>(), null, null));
    }

    // ---------------------------------------------------------------- coupon validation

    @Test
    void percentCouponAppliesServerSide() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Cold Coffee", 355)));
        when(campaignDao.findByCodeIgnoreCase("BREW3")).thenReturn(Optional.of(campaign("BREW3", "PERCENT", 10, "ACTIVE")));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);
        lenient().when(campaignEventDao.countByCampaignIdAndEventTypeAndEmail(anyInt(), anyString(), anyString())).thenReturn(0L);

        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW3", "customer@x.io");
        assertTrue(r.isCouponApplied());
        assertEquals(36, r.getDiscount()); // 10% of 355 = 35.5 -> HALF_UP -> 36 (see rounding test)
        assertEquals(319, r.getTotal());
    }

    @Test
    void percentRoundingIsDeterministicHalfUp() {
        // 10% of 355 = 35.5 -> rounds to 36 (documented HALF_UP rule) — display,
        // bill and provider all see the same resulting total (319).
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Cold Coffee", 355)));
        when(campaignDao.findByCodeIgnoreCase("BREW3")).thenReturn(Optional.of(campaign("BREW3", "PERCENT", 10, "ACTIVE")));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);

        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW3", null);
        assertEquals(36, r.getDiscount());
        assertEquals(319, r.getTotal());
        assertEquals(31900L, r.getTotalPaise());
    }

    @Test
    void flatCouponCappedAtSubtotal() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        when(campaignDao.findByCodeIgnoreCase("FLAT100")).thenReturn(Optional.of(campaign("FLAT100", "FLAT", 100, "ACTIVE")));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);

        PricingResult r = pricingService.price(cart(item(1, 1)), "FLAT100", null);
        assertEquals(90, r.getDiscount()); // capped at the 90 subtotal, never negative totals
        assertEquals(0, r.getTotal());
    }

    @Test
    void maxDiscountCapsPercentOffers() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Platter", 1000)));
        Campaign c = campaign("BREW20", "PERCENT", 20, "ACTIVE");
        c.setMaxDiscount(150);
        when(campaignDao.findByCodeIgnoreCase("BREW20")).thenReturn(Optional.of(c));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);

        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW20", null);
        assertEquals(150, r.getDiscount()); // 20% of 1000 = 200, capped at 150
        assertEquals(850, r.getTotal());
    }

    @Test
    void minOrderValueBlocksSmallCarts() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        Campaign c = campaign("BREWBIG", "PERCENT", 10, "ACTIVE");
        c.setMinOrderValue(500);
        when(campaignDao.findByCodeIgnoreCase("BREWBIG")).thenReturn(Optional.of(c));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);

        PricingResult r = pricingService.price(cart(item(1, 1)), "BREWBIG", null);
        assertFalse(r.isCouponApplied());
        assertEquals(0, r.getDiscount());
        assertEquals(90, r.getTotal());
        assertTrue(r.getCouponRejectionReason().contains("Minimum order"));
    }

    @Test
    void unknownCouponIsRejectedWithReason() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        when(campaignDao.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        PricingResult r = pricingService.price(cart(item(1, 1)), "NOPE", null);
        assertFalse(r.isCouponApplied());
        assertEquals("Coupon code not found.", r.getCouponRejectionReason());
    }

    @Test
    void inactiveCampaignIsRejected() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        when(campaignDao.findByCodeIgnoreCase("BREW3")).thenReturn(Optional.of(campaign("BREW3", "PERCENT", 10, "DRAFT")));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);
        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW3", null);
        assertFalse(r.isCouponApplied());
        assertEquals("This coupon is not active.", r.getCouponRejectionReason());
    }

    @Test
    void expiredCampaignIsRejected() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        Campaign c = campaign("BREW3", "PERCENT", 10, "ACTIVE");
        c.setEndsAt("2020-01-01");
        when(campaignDao.findByCodeIgnoreCase("BREW3")).thenReturn(Optional.of(c));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);
        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW3", null);
        assertFalse(r.isCouponApplied());
        assertEquals("This coupon has expired.", r.getCouponRejectionReason());
    }

    @Test
    void totalUsageLimitBlocksCoupon() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        Campaign c = campaign("BREW3", "PERCENT", 10, "ACTIVE");
        c.setUsageLimit(2);
        when(campaignDao.findByCodeIgnoreCase("BREW3")).thenReturn(Optional.of(c));
        when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(2L);
        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW3", null);
        assertFalse(r.isCouponApplied());
        assertTrue(r.getCouponRejectionReason().contains("usage limit"));
    }

    @Test
    void perCustomerLimitBlocksReuse() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        Campaign c = campaign("BREW3", "PERCENT", 10, "ACTIVE");
        c.setMaxUsesPerCustomer(1);
        when(campaignDao.findByCodeIgnoreCase("BREW3")).thenReturn(Optional.of(c));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);
        when(campaignEventDao.countByCampaignIdAndEventTypeAndEmail(eq(3), eq("CONVERTED"), eq("cust@x.io"))).thenReturn(1L);

        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW3", "cust@x.io");
        assertFalse(r.isCouponApplied());
        assertEquals("You have already used this coupon.", r.getCouponRejectionReason());
    }

    @Test
    void offerlessCampaignIsRejected() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        when(campaignDao.findByCodeIgnoreCase("BREWANN")).thenReturn(Optional.of(campaign("BREWANN", "NONE", null, "ACTIVE")));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);
        PricingResult r = pricingService.price(cart(item(1, 1)), "BREWANN", null);
        assertFalse(r.isCouponApplied());
        assertTrue(r.getCouponRejectionReason().contains("no discount offer"));
    }

    // ---------------------------------------------------------------- tax

    @Test
    void taxAppliesOnDiscountedBase() {
        ReflectionTestUtils.setField(pricingService, "taxPercent", 5);
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Platter", 500)));
        Campaign c = campaign("BREW50", "FLAT", 100, "ACTIVE");
        when(campaignDao.findByCodeIgnoreCase("BREW50")).thenReturn(Optional.of(c));
        lenient().when(campaignEventDao.countByCampaignIdAndEventType(anyInt(), anyString())).thenReturn(0L);

        PricingResult r = pricingService.price(cart(item(1, 1)), "BREW50", null);
        assertEquals(500, r.getSubtotal());
        assertEquals(100, r.getDiscount());
        assertEquals(20, r.getTax()); // 5% of (500-100)=400 -> 20
        assertEquals(420, r.getTotal());
        assertEquals(42000L, r.getTotalPaise());
    }

    @Test
    void zeroTaxByDefault() {
        lenient().when(productDao.findById(1)).thenReturn(Optional.of(product(1, "Dosa", 90)));
        PricingResult r = pricingService.price(cart(item(1, 1)), null, null);
        assertEquals(0, r.getTax());
        assertEquals(90, r.getTotal());
    }
}
