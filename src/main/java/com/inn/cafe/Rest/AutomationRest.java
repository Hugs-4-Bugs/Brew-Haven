package com.inn.cafe.Rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequestMapping(path = "/automation")
public interface AutomationRest {

    /** Body: { billUuid } — idempotent per bill, own bill only. */
    @PostMapping(path = "/thank-you")
    ResponseEntity<String> thankYou(@RequestBody Map<String, String> requestMap);

    /** Body: { rule: WIN_BACK_DETECTION|VIP_RECOGNITION } — admin only. */
    @PostMapping(path = "/run")
    ResponseEntity<String> runRule(@RequestBody Map<String, String> requestMap);

    @GetMapping(path = "/runs")
    ResponseEntity<Map<String, Object>> runs();

    @GetMapping(path = "/status")
    ResponseEntity<Map<String, Object>> status();
}
