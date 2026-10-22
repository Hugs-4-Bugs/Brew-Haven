package com.inn.cafe.RestImpl;

import com.inn.cafe.Rest.CustomerPreferenceRest;
import com.inn.cafe.Service.CustomerPreferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CustomerPreferenceRestImpl implements CustomerPreferenceRest {

    @Autowired
    private CustomerPreferenceService customerPreferenceService;

    @Override
    public ResponseEntity<String> getMyPreferences() { return customerPreferenceService.getMyPreferences(); }

    @Override
    public ResponseEntity<String> saveMyPreferences(Map<String, Object> requestMap) {
        Integer version = null;
        Object v = requestMap.get("version");
        if (v != null) {
            try { version = Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) { }
        }
        return customerPreferenceService.saveMyPreferences(
                requestMap.get("payload") == null ? null : String.valueOf(requestMap.get("payload")), version);
    }

    @Override
    public ResponseEntity<String> clearMyPreferences() { return customerPreferenceService.clearMyPreferences(); }
}
