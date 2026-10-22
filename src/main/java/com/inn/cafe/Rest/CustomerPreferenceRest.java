package com.inn.cafe.Rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequestMapping(path = "/customer")
public interface CustomerPreferenceRest {

    @GetMapping(path = "/preferences")
    ResponseEntity<String> getMyPreferences();

    /** Body: { payload: "<preferences JSON document>", version: 2 }. */
    @PostMapping(path = "/preferences")
    ResponseEntity<String> saveMyPreferences(@RequestBody Map<String, Object> requestMap);

    @PostMapping(path = "/preferences/clear")
    ResponseEntity<String> clearMyPreferences();
}
