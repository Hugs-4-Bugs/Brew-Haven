package com.inn.cafe.Rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequestMapping(path = "/analytics")
public interface AnalyticsRest {

    @GetMapping(path = "/overview")
    ResponseEntity<Map<String, Object>> getOverview();

    @GetMapping(path = "/customers")
    ResponseEntity<Map<String, Object>> getCustomers();

    @GetMapping(path = "/campaigns")
    ResponseEntity<Map<String, Object>> getCampaigns();

    @GetMapping(path = "/upsells")
    ResponseEntity<Map<String, Object>> getUpsells();

    @GetMapping(path = "/insights")
    ResponseEntity<Map<String, Object>> getInsights();

    @GetMapping(path = "/cart-funnel")
    ResponseEntity<Map<String, Object>> getCartFunnel();
}
