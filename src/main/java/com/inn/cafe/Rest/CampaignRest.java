package com.inn.cafe.Rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequestMapping(path = "/campaign")
public interface CampaignRest {

    @PostMapping(path = "/create")
    ResponseEntity<String> create(@RequestBody Map<String, String> requestMap);

    @GetMapping(path = "/all")
    ResponseEntity<Map<String, Object>> list();

    @PostMapping(path = "/status")
    ResponseEntity<String> setStatus(@RequestBody Map<String, String> requestMap);

    @PostMapping(path = "/send")
    ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, String> requestMap);

    /** Signed-in customer surface: active campaigns for the banner. */
    @GetMapping(path = "/active")
    ResponseEntity<Map<String, Object>> activeForCustomer();

    /** Client-reported banner interaction: {campaignId, type: VIEWED|CLICKED}. */
    @PostMapping(path = "/event")
    ResponseEntity<String> recordInteraction(@RequestBody Map<String, Object> requestMap);
}
