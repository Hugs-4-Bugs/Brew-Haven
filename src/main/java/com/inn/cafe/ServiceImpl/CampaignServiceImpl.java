package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.Campaign;
import com.inn.cafe.POJO.CampaignEvent;
import com.inn.cafe.Service.CampaignService;
import com.inn.cafe.Utils.CafeUtils;
import com.inn.cafe.Utils.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CampaignServiceImpl implements CampaignService {

    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
            Campaign.STATUS_DRAFT, Campaign.STATUS_ACTIVE, Campaign.STATUS_COMPLETED, Campaign.STATUS_ARCHIVED));
    private static final Set<String> VALID_SEGMENTS = new HashSet<>(Arrays.asList(
            "ALL", "NEW", "RETURNING", "FREQUENT", "HIGH_VALUE", "INACTIVE"));

    @Value("${cafe.analytics.high-value-threshold:1000}")
    private int highValueThreshold;

    @Autowired BillDao billDao;
    @Autowired CampaignDao campaignDao;
    @Autowired CampaignEventDao campaignEventDao;
    @Autowired JwtFilter jwtFilter;

    /**
     * Channels in preference order. EmailChannel reports configured()==false
     * when SMTP credentials are absent, so resolution naturally lands on the
     * always-available LogChannel (records, never fakes a delivery).
     */
    @Autowired List<NotificationChannel> channels;

    private NotificationChannel resolveChannel() {
        return channels.stream().filter(NotificationChannel::configured).findFirst()
                .orElseThrow(() -> new IllegalStateException("No notification channel available"));
    }

    // ------------------------------------------------------------------ create

    @Override
    public ResponseEntity<String> create(Map<String, String> requestMap) {
        try {
            if (!jwtFilter.isAdmin()) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            String name = trim(requestMap.get("name"));
            if (name.isEmpty()) {
                return CafeUtils.getResponseEntity("Campaign name is required", HttpStatus.BAD_REQUEST);
            }
            String segment = uppercase(trim(requestMap.get("segment")), "ALL");
            if (!VALID_SEGMENTS.contains(segment)) {
                return CafeUtils.getResponseEntity("Invalid segment. Allowed: " + VALID_SEGMENTS, HttpStatus.BAD_REQUEST);
            }
            String startsAt = validateDate(trim(requestMap.get("startsAt")));
            String endsAt = validateDate(trim(requestMap.get("endsAt")));
            if ("INVALID".equals(startsAt) || "INVALID".equals(endsAt)) {
                return CafeUtils.getResponseEntity("Dates must be yyyy-MM-dd", HttpStatus.BAD_REQUEST);
            }

            Campaign c = new Campaign();
            c.setName(name);
            c.setType(uppercase(trim(requestMap.get("type")), "ANNOUNCEMENT"));
            c.setSegment(segment);
            c.setOfferType(uppercase(trim(requestMap.get("offerType")), "NONE"));
            Integer value = parseIntOrNull(requestMap.get("offerValue"));
            if (value != null && (value < 0 || value > 100000)) {
                return CafeUtils.getResponseEntity("Invalid offer value", HttpStatus.BAD_REQUEST);
            }
            c.setOfferValue(value);
            c.setStartsAt(startsAt);
            c.setEndsAt(endsAt);
            c.setUsageLimit(parseIntOrNull(requestMap.get("usageLimit")));
            c.setStatus(Campaign.STATUS_DRAFT);
            c.setCreatedBy(jwtFilter.getCurrentUser());
            c.setCreatedAt(new Date());
            c = campaignDao.save(c);  // first save assigns the generated id (code is nullable)
            // Deterministic, unique, admin-visible code (never caller-supplied).
            c.setCode("BREW" + c.getId());
            c = campaignDao.save(c);
            String body = "{\"message\":\"Campaign created as DRAFT\",\"id\":" + c.getId() + ",\"code\":\"" + c.getCode() + "\"}";
            return new ResponseEntity<String>(body, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Campaign create failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ------------------------------------------------------------------ list

    @Override
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!jwtFilter.isAdmin()) {
            body.put("message", "Unauthorized Access");
            return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
        }
        List<Map<String, Object>> rows = campaignDao.findAllByOrderByIdDesc().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("type", c.getType());
            m.put("segment", c.getSegment());
            m.put("offerType", c.getOfferType());
            m.put("offerValue", c.getOfferValue());
            m.put("code", c.getCode());
            m.put("startsAt", c.getStartsAt());
            m.put("endsAt", c.getEndsAt());
            m.put("status", c.getStatus());
            m.put("usageLimit", c.getUsageLimit());
            m.put("createdBy", c.getCreatedBy());
            return m;
        }).collect(Collectors.toList());
        body.put("campaigns", rows);
        body.put("channel", resolveChannel().id());
        body.put("channelConfigured", channels.stream().anyMatch(ch -> !"NONE".equals(ch.id()) && ch.configured()));
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ status

    @Override
    public ResponseEntity<String> setStatus(Map<String, String> requestMap) {
        try {
            if (!jwtFilter.isAdmin()) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            Integer id = parseIntOrNull(requestMap.get("id"));
            String status = uppercase(trim(requestMap.get("status")), "");
            if (id == null || !VALID_STATUSES.contains(status)) {
                return CafeUtils.getResponseEntity("Valid id and status required", HttpStatus.BAD_REQUEST);
            }
            Optional<Campaign> opt = campaignDao.findById(id);
            if (!opt.isPresent()) {
                return CafeUtils.getResponseEntity("Campaign not found", HttpStatus.BAD_REQUEST);
            }
            Campaign c = opt.get();
            c.setStatus(status);
            campaignDao.save(c);
            return CafeUtils.getResponseEntity("Campaign status updated to " + status, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Campaign status failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ------------------------------------------------------------------ send

    @Override
    public ResponseEntity<Map<String, Object>> send(Map<String, String> requestMap) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if (!jwtFilter.isAdmin()) {
                body.put("message", "Unauthorized Access");
                return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
            }
            Integer id = parseIntOrNull(requestMap.get("id"));
            if (id == null) {
                body.put("message", "Campaign id required");
                return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
            }
            Optional<Campaign> opt = campaignDao.findById(id);
            if (!opt.isPresent()) {
                body.put("message", "Campaign not found");
                return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
            }
            Campaign c = opt.get();
            if (!Campaign.STATUS_ACTIVE.equals(c.getStatus())) {
                body.put("message", "Only ACTIVE campaigns can be sent. Set the campaign to ACTIVE first.");
                return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
            }

            List<String> targets = resolveSegment(c.getSegment());
            NotificationChannel channel = resolveChannel();

            int sent = 0, failed = 0, skipped = 0;
            int limit = c.getUsageLimit() == null ? Integer.MAX_VALUE : c.getUsageLimit();
            long alreadySent = campaignEventDao.countByCampaignIdAndEventType(c.getId(), CampaignEvent.TYPE_SENT);

            for (String email : targets) {
                if (alreadySent + sent >= limit) { skipped = targets.size() - sent - failed; break; }
                try {
                    channel.send(email, "Brew Haven — " + c.getName(), renderBody(c));
                    record(c.getId(), email, CampaignEvent.TYPE_SENT, channel.id(), "delivered", null, null);
                    sent++;
                } catch (Exception ex) {
                    record(c.getId(), email, CampaignEvent.TYPE_SENT, channel.id(), "failed: " + safe(ex.getMessage()), null, null);
                    failed++;
                }
            }
            body.put("sent", sent);
            body.put("failed", failed);
            body.put("skippedByLimit", skipped);
            body.put("channel", channel.id());
            if ("NONE".equals(channel.id())) {
                body.put("note", "No delivery provider is configured — sends were RECORDED as campaign events only. Configure SMTP (spring.mail.*) or add a WhatsApp/SMS channel to deliver for real.");
            }
            return new ResponseEntity<>(body, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Campaign send failed: {}", ex.getMessage());
            body.put("message", "Something Went Wrong");
            return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ------------------------------------------------------------------ customer surface

    @Override
    public ResponseEntity<Map<String, Object>> activeForCustomer() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String today = LocalDate.now().toString();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Campaign c : campaignDao.findByStatusOrderByIdDesc(Campaign.STATUS_ACTIVE)) {
                if (c.getStartsAt() != null && !c.getStartsAt().isEmpty() && today.compareTo(c.getStartsAt()) < 0) continue;
                if (c.getEndsAt() != null && !c.getEndsAt().isEmpty() && today.compareTo(c.getEndsAt()) > 0) continue;
                long conversions = campaignEventDao.countByCampaignIdAndEventType(c.getId(), CampaignEvent.TYPE_CONVERTED);
                if (c.getUsageLimit() != null && conversions >= c.getUsageLimit()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("name", c.getName());
                m.put("code", c.getCode());
                m.put("offerType", c.getOfferType());
                m.put("offerValue", c.getOfferValue());
                rows.add(m);
            }
            body.put("campaigns", rows);
            return new ResponseEntity<>(body, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("activeForCustomer failed: {}", ex.getMessage());
            body.put("campaigns", new ArrayList<>());
            return new ResponseEntity<>(body, HttpStatus.OK);
        }
    }

    @Override
    public ResponseEntity<String> recordInteraction(Map<String, Object> requestMap) {
        try {
            Integer campaignId = parseIntOrNull(String.valueOf(requestMap.get("campaignId")));
            String type = uppercase(trim(String.valueOf(requestMap.get("type"))), "");
            if (campaignId == null || (!CampaignEvent.TYPE_VIEWED.equals(type) && !CampaignEvent.TYPE_CLICKED.equals(type))) {
                return CafeUtils.getResponseEntity("Valid campaignId and type (VIEWED|CLICKED) required", HttpStatus.BAD_REQUEST);
            }
            if (!campaignDao.findById(campaignId).isPresent()) {
                return CafeUtils.getResponseEntity("Campaign not found", HttpStatus.BAD_REQUEST);
            }
            // VIEWED is high-frequency — record a click always, a view at most once per campaign+email.
            String email = jwtFilter.getCurrentUser();
            if (CampaignEvent.TYPE_VIEWED.equals(type)
                    && campaignEventDao.findByCampaignIdAndEventType(campaignId, CampaignEvent.TYPE_VIEWED).stream()
                        .anyMatch(e -> email != null && email.equalsIgnoreCase(e.getEmail()))) {
                return new ResponseEntity<String>("{\"message\":\"already recorded\"}", HttpStatus.OK);
            }
            record(campaignId, email, type, "CLIENT", "client-reported banner interaction", null, null);
            return new ResponseEntity<String>("{\"message\":\"recorded\"}", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("campaign interaction failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ------------------------------------------------------------------ shared (used by the bill flow)

    /**
     * Server-side campaign-code validation + conversion recording. Called ONLY
     * from the bill creation flow after the bill row exists. Idempotent per
     * bill: a second call for the same campaign+bill is a no-op.
     * Returns the attributed campaign or null (with reason in the out param).
     */
    public Campaign attributeConversion(String code, Integer billId, Integer revenue, StringBuilder reason) {
        if (code == null || code.trim().isEmpty()) return null;
        Optional<Campaign> opt = campaignDao.findByCodeIgnoreCase(code.trim());
        if (!opt.isPresent()) { reason.append("unknown campaign code"); return null; }
        Campaign c = opt.get();
        if (!Campaign.STATUS_ACTIVE.equals(c.getStatus())) { reason.append("campaign is not active"); return null; }
        String today = LocalDate.now().toString();
        if (c.getStartsAt() != null && !c.getStartsAt().isEmpty() && today.compareTo(c.getStartsAt()) < 0) { reason.append("campaign has not started"); return null; }
        if (c.getEndsAt() != null && !c.getEndsAt().isEmpty() && today.compareTo(c.getEndsAt()) > 0) { reason.append("campaign has ended"); return null; }
        long conversions = campaignEventDao.countByCampaignIdAndEventType(c.getId(), CampaignEvent.TYPE_CONVERTED);
        if (c.getUsageLimit() != null && conversions >= c.getUsageLimit()) { reason.append("campaign usage limit reached"); return null; }
        List<CampaignEvent> dup = campaignEventDao.findByCampaignIdAndEventTypeAndBillId(c.getId(), CampaignEvent.TYPE_CONVERTED, billId);
        if (!dup.isEmpty()) return c; // already attributed — idempotent
        record(c.getId(), jwtFilter.getCurrentUser(), CampaignEvent.TYPE_CONVERTED, "SERVER", "campaign code applied at checkout", billId, revenue);
        return c;
    }

    void record(Integer campaignId, String email, String type, String channel, String detail, Integer billId, Integer revenue) {
        CampaignEvent e = new CampaignEvent();
        e.setCampaignId(campaignId);
        e.setEmail(email == null ? null : email.toLowerCase());
        e.setEventType(type);
        e.setChannel(channel);
        e.setDetail(detail);
        e.setBillId(billId);
        e.setRevenue(revenue);
        e.setCreatedAt(new Date());
        campaignEventDao.save(e);
    }

    /** Resolves the target segment from REAL bills (never invented customers). */
    List<String> resolveSegment(String segment) {
        List<Map<String, Object>> customers = AnalyticsServiceImpl.segmentCustomers(billDao.findAll(), highValueThreshold);
        if ("ALL".equals(segment)) {
            Set<String> emails = new LinkedHashSet<>();
            for (Map<String, Object> c : customers) emails.add((String) c.get("email"));
            if (jwtFilter.getCurrentUser() != null) emails.remove(jwtFilter.getCurrentUser().toLowerCase());
            return new ArrayList<>(emails);
        }
        return customers.stream()
                .filter(c -> ((List<?>) c.get("segments")).contains(segment))
                .map(c -> (String) c.get("email"))
                .collect(Collectors.toList());
    }

    private String renderBody(Campaign c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello from Brew Haven!\n\n").append(c.getName()).append("\n");
        if (c.getOfferValue() != null) {
            sb.append("Offer: ").append(c.getOfferValue())
              .append("PERCENT".equals(c.getOfferType()) ? "% off" : "FLAT".equals(c.getOfferType()) ? " Rs. off" : "").append("\n");
        }
        sb.append("Apply code ").append(c.getCode()).append(" at checkout.\n\n— Brew Haven");
        return sb.toString();
    }

    // ------------------------------------------------------------------ small helpers

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static String uppercase(String s, String def) { return s.isEmpty() ? def : s.toUpperCase(); }

    private static String validateDate(String s) {
        if (s.isEmpty()) return "";
        try { return LocalDate.parse(s).toString(); } catch (Exception ex) { return "INVALID"; }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception ex) { return null; }
    }

    private static String safe(String s) { return s == null ? "unknown error" : s.replaceAll("[\\r\\n]", " "); }
}
