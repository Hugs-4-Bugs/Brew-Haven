package com.inn.cafe.Service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Client-reported guest interaction events (authed, rate-limited). */
public interface InteractionEventService {
    ResponseEntity<String> record(Map<String, Object> requestMap);
}
