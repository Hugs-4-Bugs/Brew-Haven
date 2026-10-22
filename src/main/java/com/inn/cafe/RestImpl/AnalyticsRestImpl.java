package com.inn.cafe.RestImpl;

import com.inn.cafe.Rest.AnalyticsRest;
import com.inn.cafe.Service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AnalyticsRestImpl implements AnalyticsRest {

    @Autowired
    private AnalyticsService analyticsService;

    @Override
    public ResponseEntity<Map<String, Object>> getOverview() { return analyticsService.getOverview(); }

    @Override
    public ResponseEntity<Map<String, Object>> getCustomers() { return analyticsService.getCustomers(); }

    @Override
    public ResponseEntity<Map<String, Object>> getCampaigns() { return analyticsService.getCampaigns(); }

    @Override
    public ResponseEntity<Map<String, Object>> getUpsells() { return analyticsService.getUpsells(); }

    @Override
    public ResponseEntity<Map<String, Object>> getInsights() { return analyticsService.getInsights(); }

    @Override
    public ResponseEntity<Map<String, Object>> getCartFunnel() { return analyticsService.getCartFunnel(); }
}
