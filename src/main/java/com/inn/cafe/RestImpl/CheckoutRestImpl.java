package com.inn.cafe.RestImpl;

import com.inn.cafe.Rest.CheckoutRest;
import com.inn.cafe.Service.CheckoutService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thin REST adapter for the checkout flow — all logic lives in
 * CheckoutServiceImpl (pricing) and CheckoutSettlementServiceImpl (transactional
 * settlement). See Rest/CheckoutRest for the endpoint contracts.
 */
@RestController
@CrossOrigin(origins = "*")
public class CheckoutRestImpl implements CheckoutRest {

    @Autowired
    private CheckoutService checkoutService;

    @Override
    public ResponseEntity<Map<String, Object>> price(Map<String, Object> requestMap) {
        return checkoutService.price(requestMap);
    }

    @Override
    public ResponseEntity<Map<String, Object>> createIntent(Map<String, Object> requestMap) {
        return checkoutService.createIntent(requestMap);
    }

    @Override
    public ResponseEntity<Map<String, Object>> intentStatus(String reference) {
        return checkoutService.intentStatus(reference);
    }

    @Override
    public ResponseEntity<String> intentQr(String reference) {
        return checkoutService.intentQr(reference);
    }

    @Override
    public ResponseEntity<Map<String, Object>> confirm(Map<String, Object> requestMap) {
        return checkoutService.confirm(requestMap);
    }

    @Override
    public ResponseEntity<String> webhook(String signatureHeader, String rawBody) {
        return checkoutService.webhook(rawBody, signatureHeader);
    }

    @Override
    public ResponseEntity<Map<String, Object>> sandboxSettle(Map<String, Object> requestMap) {
        return checkoutService.sandboxSettle(requestMap);
    }

    @Override
    public ResponseEntity<String> cancel(Map<String, Object> requestMap) {
        return checkoutService.cancel(requestMap);
    }
}
