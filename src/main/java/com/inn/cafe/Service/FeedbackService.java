package com.inn.cafe.Service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Post-order feedback (own bills only; admin listing). */
public interface FeedbackService {
    ResponseEntity<String> submit(Map<String, Object> requestMap);
    ResponseEntity<Map<String, Object>> getAll();
}
