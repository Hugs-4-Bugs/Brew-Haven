package com.inn.cafe.Service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Campaign / offer engine (admin-managed, additive).
 *  - create / list / status changes are admin-only.
 *  - send() delivers through the configured NotificationChannel; when none is
 *    configured, sends are recorded with channel=NONE — nothing is faked.
 *  - signed-in customers can list ACTIVE campaigns (banner) and report
 *    VIEWED/CLICKED; CONVERTED events are ONLY created by the bill flow
 *    (server-verified campaign code at checkout).
 */
public interface CampaignService {

    ResponseEntity<String> create(Map<String, String> requestMap);

    ResponseEntity<Map<String, Object>> list();

    ResponseEntity<String> setStatus(Map<String, String> requestMap);

    /** Deliver to the campaign's target segment; records per-recipient SENT events. */
    ResponseEntity<Map<String, Object>> send(Map<String, String> requestMap);

    /** Active campaigns for the signed-in customer's banner. */
    ResponseEntity<Map<String, Object>> activeForCustomer();

    /** Client-reported banner interaction (VIEWED / CLICKED). */
    ResponseEntity<String> recordInteraction(Map<String, Object> requestMap);
}
