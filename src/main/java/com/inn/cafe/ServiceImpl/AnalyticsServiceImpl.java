package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.DAO.InteractionEventDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.Campaign;
import com.inn.cafe.POJO.CampaignEvent;
import com.inn.cafe.POJO.InteractionEvent;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.Service.AnalyticsService;
import com.inn.cafe.Utils.CafeUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic revenue-intelligence engine. No external AI is called: every
 * insight is a rule evaluated against real rows, with the sample size that
 * produced it. This is the "AI unavailable must never break the app" promise
 * taken to its logical end — the analytics path has NO runtime dependency on
 * any AI service at all, and the insights are labelled "rule-based".
 */
@Slf4j
@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    /** uuid pattern used by the frontend bill flow: BILL-<epoch-millis>. */
    private static final Pattern UUID_MILLIS = Pattern.compile("^(?:BILL-)?(\\d{10,14})");

    private static final String NOT_ENOUGH_DATA = "Not enough data yet";

    /** High-value threshold in whole rupees (configurable, additive property). */
    @Value("${cafe.analytics.high-value-threshold:1000}")
    private int highValueThreshold;

    /** Minimum bills for "solid" time-of-day insights; below it we say so. */
    private static final int MIN_BILLS_PEAK_HOURS = 10;
    private static final int MIN_BILLS_TREND = 6;
    private static final int MIN_PAIRS_AFFINITY = 3;
    private static final int MIN_UPSELL_ACCEPTS = 5;

    @Autowired BillDao billDao;
    @Autowired ProductDao productDao;
    @Autowired CampaignDao campaignDao;
    @Autowired CampaignEventDao campaignEventDao;
    @Autowired InteractionEventDao interactionEventDao;
    @Autowired JwtFilter jwtFilter;

    // ------------------------------------------------------------------ helpers

    private boolean admin() {
        return jwtFilter.isAdmin();
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Unauthorized Access");
        return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
    }

    /** Bill date derived from the uuid-embedded epoch millis; null when unparseable. */
    public static Date billDate(Bill b) {
        if (b == null || b.getUuid() == null) return null;
        Matcher m = UUID_MILLIS.matcher(b.getUuid());
        if (!m.find()) return null;
        try {
            return new Date(Long.parseLong(m.group(1)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Parses the bill's productDetails JSON into line maps (empty on any failure). */
    static List<Map<String, Object>> billLines(Bill b) {
        try {
            JSONArray arr = CafeUtils.getJsonArrayFromString(b.getProductDetails());
            List<Map<String, Object>> lines = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Map<String, Object> line = CafeUtils.getMapFromJson(arr.getString(i));
                if (line != null) lines.add(line);
            }
            return lines;
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    /** Customer segmentation over REAL bills. Shared shape used by /analytics/customers, campaign targeting and automation rules. */
    static List<Map<String, Object>> segmentCustomers(List<Bill> bills, int highValueThreshold) {
        Map<String, List<Bill>> byEmail = new LinkedHashMap<>();
        for (Bill b : bills) {
            String email = b.getEmail() == null ? "" : b.getEmail().trim().toLowerCase();
            if (email.isEmpty()) continue;
            byEmail.computeIfAbsent(email, k -> new ArrayList<>()).add(b);
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Bill>> e : byEmail.entrySet()) {
            List<Bill> myBills = e.getValue();
            myBills.sort(Comparator.comparingInt(Bill::getId));
            int orders = myBills.size();
            int totalSpent = myBills.stream().mapToInt(x -> x.getTotal() == null ? 0 : x.getTotal()).sum();
            Date first = billDate(myBills.get(0));
            Date last = billDate(myBills.get(myBills.size() - 1));
            long lastAgoDays = last == null ? -1 : (now - last.getTime()) / 86_400_000L;

            List<String> segments = new ArrayList<>();
            if (orders == 1) segments.add("NEW");
            else if (orders <= 3) segments.add("RETURNING");
            else segments.add("FREQUENT");
            if (totalSpent >= highValueThreshold && orders >= 2) segments.add("HIGH_VALUE");
            if (lastAgoDays > 30) segments.add("INACTIVE");
            else if (lastAgoDays >= 0 && lastAgoDays <= 7) segments.add("RECENTLY_ACTIVE");

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("email", e.getKey());
            c.put("name", myBills.get(myBills.size() - 1).getName());
            c.put("orders", orders);
            c.put("totalSpent", totalSpent);
            c.put("avgOrderValue", orders == 0 ? 0 : totalSpent / orders);
            c.put("firstOrderAt", first);
            c.put("lastOrderAt", last);
            c.put("segments", segments);
            out.add(c);
        }
        out.sort((a, b2) -> Integer.compare((int) b2.get("totalSpent"), (int) a.get("totalSpent")));
        return out;
    }

    private Map<String, Integer> productTotals(List<Bill> bills, String key) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Bill b : bills) {
            for (Map<String, Object> line : billLines(b)) {
                String name = String.valueOf(line.get("name"));
                int qty = parseIntSafe(line.get(key));
                totals.merge(name, qty, Integer::sum);
            }
        }
        return totals;
    }

    private static int parseIntSafe(Object o) {
        try {
            return o == null ? 0 : (int) Math.round(Double.parseDouble(String.valueOf(o)));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static List<Map<String, Object>> topN(Map<String, Integer> totals, int n) {
        return totals.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("value", e.getValue());
                    return m;
                })
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    // ------------------------------------------------------------------ overview

    @Override
    public ResponseEntity<Map<String, Object>> getOverview() {
        if (!admin()) return unauthorized();
        List<Bill> bills = billDao.findAll();
        long now = System.currentTimeMillis();
        long day7 = 7L * 86_400_000L;

        int totalRevenue = bills.stream().mapToInt(b -> b.getTotal() == null ? 0 : b.getTotal()).sum();
        int recentRevenue = 0, priorRevenue = 0;
        for (Bill b : bills) {
            Date d = billDate(b);
            if (d == null) continue;
            long age = now - d.getTime();
            if (age <= day7) recentRevenue += b.getTotal() == null ? 0 : b.getTotal();
            else if (age <= 2 * day7) priorRevenue += b.getTotal() == null ? 0 : b.getTotal();
        }

        List<Map<String, Object>> customers = segmentCustomers(bills, highValueThreshold);
        long repeat = customers.stream().filter(c -> (int) c.get("orders") >= 2).count();
        long highValue = customers.stream().filter(c -> ((List<?>) c.get("segments")).contains("HIGH_VALUE")).count();
        long newLast7 = customers.stream()
                .filter(c -> c.get("firstOrderAt") instanceof Date && now - ((Date) c.get("firstOrderAt")).getTime() <= day7)
                .count();

        Map<String, Integer> qty = productTotals(bills, "quantity");
        Map<String, Integer> revenue = productTotals(bills, "total");

        // Slow movers: ACTIVE products never appearing on any bill line.
        Set<String> ordered = qty.keySet();
        List<String> slowMovers = new ArrayList<>();
        for (Product p : productDao.findAll()) {
            if (!"true".equalsIgnoreCase(p.getStatus())) continue;
            if (!ordered.contains(p.getName())) slowMovers.add(p.getName());
        }

        // Category performance from real bill lines.
        Map<String, Integer> categoryRevenue = new LinkedHashMap<>();
        for (Bill b : bills) {
            for (Map<String, Object> line : billLines(b)) {
                categoryRevenue.merge(String.valueOf(line.get("category")), parseIntSafe(line.get("total")), Integer::sum);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> sales = new LinkedHashMap<>();
        sales.put("totalRevenue", totalRevenue);
        sales.put("orderCount", bills.size());
        sales.put("avgOrderValue", bills.isEmpty() ? 0 : totalRevenue / bills.size());
        sales.put("revenueLast7Days", recentRevenue);
        sales.put("revenuePrior7Days", priorRevenue);
        body.put("sales", sales);

        Map<String, Object> cust = new LinkedHashMap<>();
        cust.put("customerCount", customers.size());
        cust.put("newCustomersLast7Days", newLast7);
        cust.put("repeatCustomers", repeat);
        cust.put("repeatRate", customers.isEmpty() ? 0 : Math.round(100.0 * repeat / customers.size()));
        cust.put("highValueCustomers", highValue);
        body.put("customers", cust);

        Map<String, Object> products = new LinkedHashMap<>();
        products.put("bestSellersByQuantity", topN(qty, 5));
        products.put("bestSellersByRevenue", topN(revenue, 5));
        products.put("slowMovers", slowMovers);
        products.put("categoryPerformance", topN(categoryRevenue, 10));
        products.put("note", "Best sellers and category performance are computed from real bill line items; slow movers are active products with zero orders.");
        body.put("products", products);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ customers (CRM)

    @Override
    public ResponseEntity<Map<String, Object>> getCustomers() {
        if (!admin()) return unauthorized();
        List<Bill> bills = billDao.findAll();
        List<Map<String, Object>> customers = segmentCustomers(bills, highValueThreshold);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String seg : Arrays.asList("NEW", "RETURNING", "FREQUENT", "HIGH_VALUE", "INACTIVE", "RECENTLY_ACTIVE")) {
            counts.put(seg, customers.stream().filter(c -> ((List<?>) c.get("segments")).contains(seg)).count());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customers", customers);
        body.put("segmentCounts", counts);
        body.put("highValueThreshold", highValueThreshold);
        body.put("note", "Segments are computed from real bills only — customers with no orders are not invented.");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ campaigns

    @Override
    public ResponseEntity<Map<String, Object>> getCampaigns() {
        if (!admin()) return unauthorized();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Campaign c : campaignDao.findAllByOrderByIdDesc()) {
            List<CampaignEvent> events = campaignEventDao.findByCampaignIdOrderByIdAsc(c.getId());
            long sent = events.stream().filter(e -> CampaignEvent.TYPE_SENT.equals(e.getEventType())).count();
            long viewed = events.stream().filter(e -> CampaignEvent.TYPE_VIEWED.equals(e.getEventType())).count();
            long clicked = events.stream().filter(e -> CampaignEvent.TYPE_CLICKED.equals(e.getEventType())).count();
            List<CampaignEvent> converted = new ArrayList<>();
            for (CampaignEvent e : events) {
                if (CampaignEvent.TYPE_CONVERTED.equals(e.getEventType())) converted.add(e);
            }
            int revenue = converted.stream().mapToInt(e -> e.getRevenue() == null ? 0 : e.getRevenue()).sum();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("name", c.getName());
            row.put("code", c.getCode());
            row.put("status", c.getStatus());
            row.put("segment", c.getSegment());
            row.put("offerType", c.getOfferType());
            row.put("offerValue", c.getOfferValue());
            row.put("usageLimit", c.getUsageLimit());
            row.put("sent", sent);
            row.put("viewed", viewed);
            row.put("clicked", clicked);
            row.put("converted", converted.size());
            row.put("revenue", revenue);
            row.put("conversionRate", clicked == 0 ? 0 : Math.round(100.0 * converted.size() / clicked));
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("campaigns", rows);
        body.put("attributionNote", "Revenue is attributed ONLY from server-verified conversion events recorded at bill creation (campaign code applied at checkout). Viewed/clicked are client-reported signals.");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ upsells

    @Override
    public ResponseEntity<Map<String, Object>> getUpsells() {
        if (!admin()) return unauthorized();
        List<InteractionEvent> shown = interactionEventDao.findByEventType("UPSELL_SHOWN");
        List<InteractionEvent> accepted = interactionEventDao.findByEventType("UPSELL_ACCEPTED");

        Map<Integer, int[]> byProduct = new LinkedHashMap<>(); // [shown, accepted]
        for (InteractionEvent e : shown) {
            if (e.getProductId() == null) continue;
            byProduct.computeIfAbsent(e.getProductId(), k -> new int[2])[0]++;
        }
        for (InteractionEvent e : accepted) {
            if (e.getProductId() == null) continue;
            byProduct.computeIfAbsent(e.getProductId(), k -> new int[2])[1]++;
        }
        Map<Integer, String> names = new HashMap<>();
        for (Product p : productDao.findAll()) names.put(p.getId(), p.getName());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : byProduct.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", e.getKey());
            row.put("productName", names.getOrDefault(e.getKey(), "#" + e.getKey()));
            row.put("shown", e.getValue()[0]);
            row.put("accepted", e.getValue()[1]);
            row.put("acceptRate", e.getValue()[0] == 0 ? 0 : Math.round(100.0 * e.getValue()[1] / e.getValue()[0]));
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare((int) b.get("accepted"), (int) a.get("accepted")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upsells", rows);
        body.put("totalShown", shown.size());
        body.put("totalAccepted", accepted.size());
        body.put("acceptRate", shown.isEmpty() ? 0 : Math.round(100.0 * accepted.size() / shown.size()));
        body.put("note", "Upsell metrics are client-reported interaction events from signed-in sessions — labelled as measurement signals, not server-verified facts.");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ insights

    @Override
    public ResponseEntity<Map<String, Object>> getInsights() {
        if (!admin()) return unauthorized();
        List<Bill> bills = billDao.findAll();
        List<Map<String, Object>> insights = new ArrayList<>();

        // 1) Peak sales window (hour-of-day histogram from bill timestamps).
        Map<Integer, Integer> hourHist = new TreeMap<>();
        int dated = 0;
        for (Bill b : bills) {
            Date d = billDate(b);
            if (d == null) continue;
            dated++;
            hourHist.merge(new java.util.Calendar.Builder().setInstant(d).build().get(java.util.Calendar.HOUR_OF_DAY), 1, Integer::sum);
        }
        if (dated >= MIN_BILLS_PEAK_HOURS) {
            int bestHour = 0, bestCount = -1;
            for (Map.Entry<Integer, Integer> e : hourHist.entrySet()) {
                if (e.getValue() > bestCount) { bestCount = e.getValue(); bestHour = e.getKey(); }
            }
            insights.add(insight("PEAK_HOURS", "Sales are strongest around " + hourLabel(bestHour),
                    bestCount + " of " + dated + " dated bills were created in the " + hourLabel(bestHour)
                            + " hour. Consider staffing and upsell prompts around this window.",
                    dated, dated >= 2 * MIN_BILLS_PEAK_HOURS ? "solid" : "limited"));
        } else {
            insights.add(noData("PEAK_HOURS", MIN_BILLS_PEAK_HOURS + " dated bills are needed to name a peak sales window (have " + dated + ")."));
        }

        // 2) Product affinity (co-occurrence within the same bill).
        Map<String, Integer> pairs = new HashMap<>();
        for (Bill b : bills) {
            Set<String> names = new TreeSet<>();
            for (Map<String, Object> line : billLines(b)) names.add(String.valueOf(line.get("name")));
            List<String> list = new ArrayList<>(names);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    pairs.merge(list.get(i) + " + " + list.get(j), 1, Integer::sum);
                }
            }
        }
        Map.Entry<String, Integer> bestPair = pairs.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (bestPair != null && bestPair.getValue() >= MIN_PAIRS_AFFINITY) {
            insights.add(insight("AFFINITY", "Customers ordering " + bestPair.getKey().split(" \\+ ")[0] + " frequently also order " + bestPair.getKey().split(" \\+ ")[1],
                    "These two products appeared together on " + bestPair.getValue() + " bills — a natural pairing-suggestion candidate.",
                    bestPair.getValue(), bestPair.getValue() >= 2 * MIN_PAIRS_AFFINITY ? "solid" : "limited"));
        } else {
            insights.add(noData("AFFINITY", "A product pair must co-occur on " + MIN_PAIRS_AFFINITY + " bills before an affinity insight is reported."));
        }

        // 3) Week-over-week revenue trend.
        long now = System.currentTimeMillis();
        long day7 = 7L * 86_400_000L;
        int recent = 0, prior = 0;
        for (Bill b : bills) {
            Date d = billDate(b);
            if (d == null) continue;
            long age = now - d.getTime();
            if (age <= day7) recent += b.getTotal() == null ? 0 : b.getTotal();
            else if (age <= 2 * day7) prior += b.getTotal() == null ? 0 : b.getTotal();
        }
        if (recent + prior > 0 && countRecent(bills, day7) >= MIN_BILLS_TREND / 2) {
            String direction = recent >= prior ? "up" : "down";
            int pct = prior == 0 ? 100 : (int) Math.abs(Math.round(100.0 * (recent - prior) / prior));
            insights.add(insight("TREND", "Revenue is " + direction + " " + pct + "% compared with the previous 7 days",
                    "Last 7 days: Rs." + recent + " vs prior 7 days: Rs." + prior + ".",
                    countRecent(bills, 2 * day7), "limited"));
        } else {
            insights.add(noData("TREND", "At least " + MIN_BILLS_TREND + " bills across the last two weeks are needed for a trend insight."));
        }

        // 4) Campaign comparison (server-verified conversions only).
        List<Campaign> withConversions = new ArrayList<>();
        Map<Integer, Integer> convRevenue = new HashMap<>();
        for (Campaign c : campaignDao.findAll()) {
            List<CampaignEvent> conv = campaignEventDao.findByCampaignIdAndEventType(c.getId(), CampaignEvent.TYPE_CONVERTED);
            if (!conv.isEmpty()) {
                withConversions.add(c);
                convRevenue.put(c.getId(), conv.stream().mapToInt(e -> e.getRevenue() == null ? 0 : e.getRevenue()).sum());
            }
        }
        if (withConversions.size() >= 2) {
            withConversions.sort(Comparator.comparingInt(c -> -convRevenue.get(c.getId())));
            Campaign a = withConversions.get(0), b = withConversions.get(1);
            insights.add(insight("CAMPAIGN_COMPARE", "Campaign '" + a.getName() + "' generated more revenue than '" + b.getName() + "'",
                    "Rs." + convRevenue.get(a.getId()) + " vs Rs." + convRevenue.get(b.getId()) + " in server-verified attributed revenue.",
                    convRevenue.get(a.getId()) + convRevenue.get(b.getId()), "solid"));
        } else {
            insights.add(noData("CAMPAIGN_COMPARE", "Two campaigns with at least one verified conversion each are needed for a comparison."));
        }

        // 5) Slow movers (always real — derived from products vs orders).
        Set<String> orderedNames = productTotals(bills, "quantity").keySet();
        List<String> never = new ArrayList<>();
        for (Product p : productDao.findAll()) {
            if ("true".equalsIgnoreCase(p.getStatus()) && !orderedNames.contains(p.getName())) never.add(p.getName());
        }
        if (!never.isEmpty()) {
            insights.add(insight("SLOW_MOVERS", never.size() + " active product" + (never.size() == 1 ? "" : "s") + " never appeared on a bill",
                    "No orders recorded for: " + String.join(", ", never) + ". Consider featuring them or reviewing placement.",
                    bills.size(), "solid"));
        }

        // 6) Upsell acceptance leader.
        List<InteractionEvent> accepted = interactionEventDao.findByEventType("UPSELL_ACCEPTED");
        if (accepted.size() >= MIN_UPSELL_ACCEPTS) {
            Map<Integer, Integer> byProduct = new HashMap<>();
            for (InteractionEvent e : accepted) {
                if (e.getProductId() != null) byProduct.merge(e.getProductId(), 1, Integer::sum);
            }
            Map<Integer, String> names = new HashMap<>();
            for (Product p : productDao.findAll()) names.put(p.getId(), p.getName());
            Map.Entry<Integer, Integer> top = byProduct.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null) {
                insights.add(insight("UPSELL_LEADER", "'" + names.getOrDefault(top.getKey(), "#" + top.getKey()) + "' is the most accepted pairing suggestion",
                        top.getValue() + " accepted suggestions. Client-reported signal from signed-in sessions.",
                        accepted.size(), "limited"));
            }
        } else {
            insights.add(noData("UPSELL_LEADER", MIN_UPSELL_ACCEPTS + " accepted pairing suggestions are needed before drawing conclusions."));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("insights", insights);
        body.put("engine", "rule-based");
        body.put("note", "Every insight is computed live from real system rows (no external AI service is called). Each carries its sample size and a confidence label; entries below their data threshold honestly report '" + NOT_ENOUGH_DATA + "'.");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ cart funnel

    @Override
    public ResponseEntity<Map<String, Object>> getCartFunnel() {
        if (!admin()) return unauthorized();
        long created = interactionEventDao.findByEventType("CART_CREATED").size();
        long checkouts = interactionEventDao.findByEventType("CHECKOUT_STARTED").size();
        long orders = interactionEventDao.findByEventType("ORDER_COMPLETED").size();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cartCreated", created);
        body.put("checkoutStarted", checkouts);
        body.put("orderCompleted", orders);
        body.put("abandonmentRate", checkouts == 0 ? 0 : Math.round(100.0 * (checkouts - Math.min(orders, checkouts)) / checkouts));
        body.put("note", "Funnel counts are client-reported events from signed-in sessions. Recovery messaging is available through a win-back campaign and requires a configured channel plus explicit admin action.");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ private

    private static int countRecent(List<Bill> bills, long windowMillis) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Bill b : bills) {
            Date d = billDate(b);
            if (d != null && now - d.getTime() <= windowMillis) n++;
        }
        return n;
    }

    private static String hourLabel(int hour) {
        int h = hour % 12 == 0 ? 12 : hour % 12;
        return h + (hour < 12 ? " AM" : " PM");
    }

    private static Map<String, Object> insight(String type, String title, String body, Object sampleSize, String confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("body", body);
        m.put("sampleSize", sampleSize);
        m.put("confidence", confidence);
        m.put("source", "rule-based");
        m.put("enoughData", true);
        return m;
    }

    private static Map<String, Object> noData(String type, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", NOT_ENOUGH_DATA);
        m.put("body", reason);
        m.put("sampleSize", 0);
        m.put("confidence", "none");
        m.put("source", "rule-based");
        m.put("enoughData", false);
        return m;
    }
}
