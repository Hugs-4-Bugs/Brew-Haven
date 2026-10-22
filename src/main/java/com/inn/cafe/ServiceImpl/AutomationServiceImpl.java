package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.AutomationRunDao;
import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.AutomationRun;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.Campaign;
import com.inn.cafe.Service.AutomationService;
import com.inn.cafe.Utils.CafeUtils;
import com.inn.cafe.Utils.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agentic automation — DETECT -> ANALYZE -> DECIDE -> ACT -> MEASURE, with
 * every run audited in automation_runs.
 *
 * Safety model (spec §15):
 *  - ORDER_THANK_YOU: any signed-in user may trigger it for THEIR OWN bill
 *    (idempotent per bill uuid). It sends an email only when a channel is
 *    genuinely configured; otherwise the run is recorded as SKIPPED with an
 *    honest reason. It is ALSO triggered automatically by the frontend after
 *    checkout, but never sends anything that was not already shown in the UI.
 *  - WIN_BACK_DETECTION / VIP_RECOGNITION: admin-only, manual. They create a
 *    DRAFT campaign from real segment data and NEVER send anything by
 *    themselves — the admin reviews and explicitly activates + sends.
 *  - The nightly scheduled sweep is config-gated (cafe.automation.scheduled-
 *    enabled, default false) and only RECORDS detections; it cannot send.
 */
@Slf4j
@Service
public class AutomationServiceImpl implements AutomationService {

    public static final String RULE_THANK_YOU = "ORDER_THANK_YOU";
    public static final String RULE_WIN_BACK = "WIN_BACK_DETECTION";
    public static final String RULE_VIP = "VIP_RECOGNITION";

    @Value("${cafe.analytics.high-value-threshold:1000}")
    private int highValueThreshold;

    @Value("${cafe.automation.scheduled-enabled:false}")
    private boolean scheduledEnabled;

    @Autowired AutomationRunDao runDao;
    @Autowired BillDao billDao;
    @Autowired CampaignDao campaignDao;
    @Autowired CampaignEventDao campaignEventDao;
    @Autowired JwtFilter jwtFilter;
    @Autowired List<NotificationChannel> channels;
    @Autowired CampaignServiceImpl campaignService;
    @Autowired AnalyticsServiceImpl analytics; // segment computation (real bills)

    private NotificationChannel deliveryChannel() {
        return channels.stream().filter(NotificationChannel::configured)
                .filter(ch -> !"NONE".equals(ch.id())).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ thank you

    @Override
    public ResponseEntity<String> thankYou(Map<String, String> requestMap) {
        try {
            String email = jwtFilter.getCurrentUser();
            String billUuid = requestMap.get("billUuid") == null ? "" : requestMap.get("billUuid").trim();
            if (email == null || billUuid.isEmpty()) {
                return CafeUtils.getResponseEntity("billUuid required", HttpStatus.BAD_REQUEST);
            }
            Bill bill = billDao.findAll().stream()
                    .filter(b -> billUuid.equals(b.getUuid()) && email.equalsIgnoreCase(b.getEmail()))
                    .findFirst().orElse(null);
            if (bill == null) {
                return CafeUtils.getResponseEntity("Bill not found for this account", HttpStatus.BAD_REQUEST);
            }
            String dedupeKey = RULE_THANK_YOU + ":" + billUuid;
            if (runDao.findByDedupeKey(dedupeKey) != null) {
                return new ResponseEntity<String>("{\"message\":\"already sent\"}", HttpStatus.OK);
            }
            NotificationChannel channel = deliveryChannel();
            JSONObject detail = new JSONObject();
            detail.put("billUuid", billUuid);
            detail.put("customer", email);
            detail.put("total", bill.getTotal());

            AutomationRun run = newRun(RULE_THANK_YOU, "MANUAL", email);
            run.setDedupeKey(dedupeKey);
            if (channel == null) {
                run.setStatus(AutomationRun.STATUS_SKIPPED);
                run.setChannel("NONE");
                detail.put("reason", "No delivery provider configured — thank-you was shown in the app UI and recorded here for audit only.");
                run.setActionCount(0);
            } else {
                try {
                    channel.send(email.toLowerCase(), "Thank you for your order — Brew Haven",
                            "Thank you for your order (bill " + billUuid + ", Rs." + bill.getTotal()
                                    + "). We hope to see you again soon!\n\n— Brew Haven");
                    run.setStatus(AutomationRun.STATUS_SUCCESS);
                    run.setChannel(channel.id());
                    run.setActionCount(1);
                    detail.put("sent", true);
                } catch (Exception ex) {
                    run.setStatus(AutomationRun.STATUS_FAILED);
                    run.setChannel(channel.id());
                    run.setActionCount(0);
                    detail.put("error", String.valueOf(ex.getMessage()));
                }
            }
            run.setDetail(detail.toString());
            runDao.save(run);
            return new ResponseEntity<String>("{\"message\":\"" + run.getStatus() + "\"}", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("thankYou failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ------------------------------------------------------------------ detection rules

    @Override
    public ResponseEntity<String> runRule(Map<String, String> requestMap) {
        try {
            if (!jwtFilter.isAdmin()) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            String rule = requestMap.get("rule") == null ? "" : requestMap.get("rule").trim().toUpperCase();
            if (RULE_WIN_BACK.equals(rule)) return winBack();
            if (RULE_VIP.equals(rule)) return vip();
            return CafeUtils.getResponseEntity("Unknown rule. Available: " + RULE_WIN_BACK + ", " + RULE_VIP, HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            log.error("runRule failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<String> winBack() throws Exception {
        List<Map<String, Object>> inactive = analytics.segmentCustomers(billDao.findAll(), highValueThreshold)
                .stream().filter(c -> ((List<?>) c.get("segments")).contains("INACTIVE"))
                .collect(Collectors.toList());
        if (inactive.isEmpty()) {
            AutomationRun run = newRun(RULE_WIN_BACK, "MANUAL", jwtFilter.getCurrentUser());
            run.setStatus(AutomationRun.STATUS_SKIPPED);
            run.setDetectedCount(0);
            run.setDetail(new JSONObject().put("reason", "No inactive customers detected (no customer with orders whose last order is older than 30 days).").toString());
            runDao.save(run);
            return CafeUtils.getResponseEntity("{\"message\":\"No inactive customers detected — nothing to do\"}", HttpStatus.OK);
        }
        Campaign draft = createDraftCampaign("Win-back campaign", "WINBACK", "INACTIVE",
                "Customers with no order in the last 30 days — " + inactive.size() + " detected.");
        AutomationRun run = newRun(RULE_WIN_BACK, "MANUAL", jwtFilter.getCurrentUser());
        run.setStatus(AutomationRun.STATUS_SUCCESS);
        run.setDetectedCount(inactive.size());
        run.setActionCount(1);
        run.setChannel("NONE");
        run.setDetail(new JSONObject()
                .put("detectedEmails", new JSONArray(inactive.stream().map(c -> c.get("email")).collect(Collectors.toList())))
                .put("createdCampaign", draft.getId())
                .put("note", "DRAFT campaign created — review and activate + send manually. Nothing was sent automatically.")
                .toString());
        runDao.save(run);
        return CafeUtils.getResponseEntity("{\"message\":\"DRAFT win-back campaign created for " + inactive.size() + " inactive customer(s)\",\"campaignId\":" + draft.getId() + "}", HttpStatus.OK);
    }

    private ResponseEntity<String> vip() throws Exception {
        List<Map<String, Object>> vip = analytics.segmentCustomers(billDao.findAll(), highValueThreshold)
                .stream().filter(c -> ((List<?>) c.get("segments")).contains("HIGH_VALUE"))
                .collect(Collectors.toList());
        if (vip.isEmpty()) {
            AutomationRun run = newRun(RULE_VIP, "MANUAL", jwtFilter.getCurrentUser());
            run.setStatus(AutomationRun.STATUS_SKIPPED);
            run.setDetectedCount(0);
            run.setDetail(new JSONObject().put("reason", "No high-value customers detected yet (threshold Rs." + highValueThreshold + " across 2+ orders).").toString());
            runDao.save(run);
            return CafeUtils.getResponseEntity("{\"message\":\"No high-value customers detected yet\"}", HttpStatus.OK);
        }
        Campaign draft = createDraftCampaign("VIP appreciation campaign", "VIP", "HIGH_VALUE",
                "High-value customers (Rs." + highValueThreshold + "+ across 2+ orders) — " + vip.size() + " detected.");
        AutomationRun run = newRun(RULE_VIP, "MANUAL", jwtFilter.getCurrentUser());
        run.setStatus(AutomationRun.STATUS_SUCCESS);
        run.setDetectedCount(vip.size());
        run.setActionCount(1);
        run.setChannel("NONE");
        run.setDetail(new JSONObject()
                .put("detectedEmails", new JSONArray(vip.stream().map(c -> c.get("email")).collect(Collectors.toList())))
                .put("createdCampaign", draft.getId())
                .put("note", "DRAFT campaign created — review and activate + send manually. Nothing was sent automatically.")
                .toString());
        runDao.save(run);
        return CafeUtils.getResponseEntity("{\"message\":\"DRAFT VIP campaign created for " + vip.size() + " high-value customer(s)\",\"campaignId\":" + draft.getId() + "}", HttpStatus.OK);
    }

    private Campaign createDraftCampaign(String name, String type, String segment, String note) {
        Campaign c = new Campaign();
        c.setName(name + " — " + new Date());
        c.setType(type);
        c.setSegment(segment);
        c.setOfferType("NONE");
        c.setStatus(Campaign.STATUS_DRAFT);
        c.setCreatedBy("automation");
        c.setCreatedAt(new Date());
        c = campaignDao.save(c);
        c.setCode("BREW" + c.getId());
        campaignDao.save(c);
        return c;
    }

    // ------------------------------------------------------------------ runs + status

    @Override
    public ResponseEntity<Map<String, Object>> runs() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!jwtFilter.isAdmin()) {
            body.put("message", "Unauthorized Access");
            return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
        }
        List<Map<String, Object>> rows = runDao.findTop100ByOrderByIdDesc().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("rule", r.getRule());
            m.put("trigger", r.getTriggerType());
            m.put("status", r.getStatus());
            m.put("detectedCount", r.getDetectedCount());
            m.put("actionCount", r.getActionCount());
            m.put("channel", r.getChannel());
            m.put("detail", r.getDetail());
            m.put("createdBy", r.getCreatedBy());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        body.put("runs", rows);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!jwtFilter.isAdmin()) {
            body.put("message", "Unauthorized Access");
            return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
        }
        List<Map<String, Object>> chans = channels.stream().map(ch -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ch.id());
            m.put("configured", ch.configured());
            return m;
        }).collect(Collectors.toList());
        body.put("channels", chans);
        body.put("scheduledEnabled", scheduledEnabled);
        body.put("rules", Arrays.asList(RULE_THANK_YOU, RULE_WIN_BACK, RULE_VIP));
        body.put("note", "Detection rules create DRAFT campaigns only — sending always requires explicit admin action and a configured channel.");
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ nightly detection sweep (record-only, config-gated OFF by default)

    /**
     * Records nightly detections (inactive/high-value counts) without creating
     * or sending anything. Disabled unless cafe.automation.scheduled-enabled=true.
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void nightlyDetection() {
        if (!scheduledEnabled) return;
        try {
            List<Map<String, Object>> customers = analytics.segmentCustomers(billDao.findAll(), highValueThreshold);
            long inactive = customers.stream().filter(c -> ((List<?>) c.get("segments")).contains("INACTIVE")).count();
            long vip = customers.stream().filter(c -> ((List<?>) c.get("segments")).contains("HIGH_VALUE")).count();
            AutomationRun run = newRun("NIGHTLY_DETECTION", "SCHEDULED", "system");
            run.setStatus(AutomationRun.STATUS_SUCCESS);
            run.setDetectedCount((int) (inactive + vip));
            run.setActionCount(0);
            run.setChannel("NONE");
            run.setDetail(new JSONObject().put("inactive", inactive).put("highValue", vip).toString());
            runDao.save(run);
        } catch (Exception ex) {
            log.error("nightlyDetection failed: {}", ex.getMessage());
        }
    }

    private AutomationRun newRun(String rule, String trigger, String by) {
        AutomationRun run = new AutomationRun();
        run.setRule(rule);
        run.setTriggerType(trigger);
        run.setStatus(AutomationRun.STATUS_SUCCESS);
        run.setDetectedCount(0);
        run.setActionCount(0);
        run.setCreatedBy(by);
        run.setCreatedAt(new Date());
        return run;
    }
}
