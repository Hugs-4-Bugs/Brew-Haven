package com.inn.cafe.Service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Audited automation rules (DETECT -> ANALYZE -> DECIDE -> ACT -> MEASURE). */
public interface AutomationService {
    /** Idempotent per-bill thank-you (own bill only; sends only via a configured channel). */
    ResponseEntity<String> thankYou(Map<String, String> requestMap);

    /** Admin-triggered detection rules (create DRAFT campaigns, never send). */
    ResponseEntity<String> runRule(Map<String, String> requestMap);

    ResponseEntity<Map<String, Object>> runs();

    ResponseEntity<Map<String, Object>> status();
}
