package com.inn.cafe.Service;

import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.DAO.InteractionEventDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.Campaign;
import com.inn.cafe.POJO.CampaignEvent;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.ServiceImpl.AnalyticsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AnalyticsServiceTest — verifies the deterministic revenue-intelligence
 * engine: segmentation from real bills, honest "not enough data" insights,
 * and admin authorization. Grounding rule: no product/order data -> no claim.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @InjectMocks
    private AnalyticsServiceImpl analytics;

    @Mock private BillDao billDao;
    @Mock private ProductDao productDao;
    @Mock private CampaignDao campaignDao;
    @Mock private CampaignEventDao campaignEventDao;
    @Mock private InteractionEventDao interactionEventDao;
    @Mock private JwtFilter jwtFilter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(analytics, "highValueThreshold", 1000);
    }

    private Bill bill(String uuid, String email, int total, String productDetails) {
        Bill b = new Bill();
        b.setId(new Random().nextInt(100000));
        b.setUuid(uuid);
        b.setName("Customer");
        b.setEmail(email);
        b.setContactNumber("9999999999");
        b.setPaymentMethod("Cash");
        b.setTotal(total);
        b.setProductDetails(productDetails);
        return b;
    }

    @Test
    void nonAdminGetsUnauthorized() {
        when(jwtFilter.isAdmin()).thenReturn(false);
        assertEquals(401, analytics.getOverview().getStatusCodeValue());
        assertEquals(401, analytics.getCustomers().getStatusCodeValue());
        assertEquals(401, analytics.getInsights().getStatusCodeValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void segmentation_computesRealSegmentsFromBills() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        // a@x: 1 order (NEW + RECENTLY_ACTIVE); b@x: 5 orders, 2500 spent (FREQUENT + HIGH_VALUE)
        List<Bill> bills = new ArrayList<>();
        bills.add(bill("BILL-" + (System.currentTimeMillis() - 86_400_000L), "a@x", 200,
                "[{\"name\":\"Masala Dosa\",\"category\":\"Dosa\",\"quantity\":1,\"price\":99,\"total\":99}]"));
        for (int i = 0; i < 5; i++) {
            bills.add(bill("BILL-" + (System.currentTimeMillis() - i * 86_400_000L), "b@x", 500,
                    "[{\"name\":\"Veggie Pizza\",\"category\":\"Pizza\",\"quantity\":2,\"price\":149,\"total\":298}]"));
        }
        when(billDao.findAll()).thenReturn(bills);

        Map<String, Object> body = analytics.getCustomers().getBody();
        List<Map<String, Object>> customers = (List<Map<String, Object>>) body.get("customers");
        assertEquals(2, customers.size());

        Map<String, Object> b = customers.stream().filter(c -> c.get("email").equals("b@x")).findFirst().orElseThrow();
        assertEquals(5, b.get("orders"));
        assertEquals(2500, b.get("totalSpent"));
        List<String> segs = (List<String>) b.get("segments");
        assertTrue(segs.contains("FREQUENT"));
        assertTrue(segs.contains("HIGH_VALUE"));

        Map<String, Object> a = customers.stream().filter(c -> c.get("email").equals("a@x")).findFirst().orElseThrow();
        assertTrue(((List<String>) a.get("segments")).contains("NEW"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void insights_sayNotEnoughData_whenBelowThresholds() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        when(billDao.findAll()).thenReturn(Collections.emptyList()); // no orders at all
        when(productDao.findAll()).thenReturn(Collections.emptyList());
        when(campaignDao.findAll()).thenReturn(Collections.emptyList());
        when(interactionEventDao.findByEventType(anyString())).thenReturn(Collections.emptyList());

        Map<String, Object> body = analytics.getInsights().getBody();
        List<Map<String, Object>> insights = (List<Map<String, Object>>) body.get("insights");
        assertFalse(insights.isEmpty());
        // With zero data every non-optional insight must be an honest "Not enough data yet".
        for (String type : Arrays.asList("PEAK_HOURS", "AFFINITY", "TREND", "CAMPAIGN_COMPARE", "UPSELL_LEADER")) {
            Map<String, Object> insight = insights.stream().filter(i -> type.equals(i.get("type"))).findFirst().orElseThrow();
            assertEquals("Not enough data yet", insight.get("title"));
            assertEquals(false, insight.get("enoughData"));
        }
        assertEquals("rule-based", body.get("engine"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overview_computesBestSellersAndSlowMoversFromRealLines() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        Bill b = bill("BILL-" + System.currentTimeMillis(), "c@x", 149,
                "[{\"name\":\"Veggie Pizza\",\"category\":\"Pizza\",\"quantity\":3,\"price\":149,\"total\":447}]");
        when(billDao.findAll()).thenReturn(new ArrayList<>(List.of(b)));

        Product ordered = new Product();
        ordered.setId(1);
        ordered.setName("Veggie Pizza");
        ordered.setStatus("true");
        Product never = new Product();
        never.setId(2);
        never.setName("Litti Chokha");
        never.setStatus("true");
        when(productDao.findAll()).thenReturn(Arrays.asList(ordered, never));

        Map<String, Object> body = analytics.getOverview().getBody();
        Map<String, Object> products = (Map<String, Object>) body.get("products");
        List<Map<String, Object>> best = (List<Map<String, Object>>) products.get("bestSellersByQuantity");
        assertEquals(1, best.size());
        assertEquals("Veggie Pizza", best.get(0).get("name"));
        assertEquals(3, best.get(0).get("value"));
        assertEquals(List.of("Litti Chokha"), products.get("slowMovers"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void campaignAnalytics_onlyAttributesServerVerifiedConversions() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        Campaign c = new Campaign();
        c.setId(7);
        c.setName("Test");
        c.setCode("BREW7");
        c.setStatus("ACTIVE");
        c.setSegment("ALL");
        when(campaignDao.findAllByOrderByIdDesc()).thenReturn(List.of(c));

        CampaignEvent sent = new CampaignEvent();
        sent.setCampaignId(7);
        sent.setEventType(CampaignEvent.TYPE_SENT);
        CampaignEvent viewed = new CampaignEvent();
        viewed.setCampaignId(7);
        viewed.setEventType(CampaignEvent.TYPE_VIEWED);
        CampaignEvent converted = new CampaignEvent();
        converted.setCampaignId(7);
        converted.setEventType(CampaignEvent.TYPE_CONVERTED);
        converted.setRevenue(447);
        when(campaignEventDao.findByCampaignIdOrderByIdAsc(7)).thenReturn(Arrays.asList(sent, viewed, converted));

        Map<String, Object> body = analytics.getCampaigns().getBody();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("campaigns");
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("sent"));
        assertEquals(1, rows.get(0).get("converted"));
        assertEquals(447, rows.get(0).get("revenue"));
        assertTrue(String.valueOf(body.get("attributionNote")).contains("server-verified"));
    }

    @Test
    void billDate_parsesLegacyAndPlainMillisUuids() {
        Date d = AnalyticsServiceImpl.billDate(bill("BILL-1792583533500", "x@x", 1, "[]"));
        assertNotNull(d);
        assertEquals(1792583533500L, d.getTime());
        assertNull(AnalyticsServiceImpl.billDate(bill("not-a-timestamp", "x@x", 1, "[]")));
        assertNull(AnalyticsServiceImpl.billDate(null));
    }
}
