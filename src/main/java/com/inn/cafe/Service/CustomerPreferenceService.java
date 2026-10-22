package com.inn.cafe.Service;

import org.springframework.http.ResponseEntity;

/** Server-side preferences for signed-in customers (see CustomerPreferenceServiceImpl). */
public interface CustomerPreferenceService {
    ResponseEntity<String> getMyPreferences();
    ResponseEntity<String> saveMyPreferences(String payload, Integer version);
    ResponseEntity<String> clearMyPreferences();
}
