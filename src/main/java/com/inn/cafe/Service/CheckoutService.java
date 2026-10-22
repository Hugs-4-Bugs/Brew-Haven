package com.inn.cafe.Service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Checkout orchestration — pricing, payment intents, verification, webhook. */
public interface CheckoutService {

    /** POST /checkout/price — authoritative pricing + coupon validation (APPLY). */
    ResponseEntity<Map<String, Object>> price(Map<String, Object> requestMap);

    /** POST /checkout/intent — idempotent payment-intent creation (server-priced). */
    ResponseEntity<Map<String, Object>> createIntent(Map<String, Object> requestMap);

    /** GET /checkout/intent/{reference} — status polling. */
    ResponseEntity<Map<String, Object>> intentStatus(String reference);

    /** GET /checkout/qr/{reference} — UPI QR for the server-computed amount. */
    ResponseEntity<String> intentQr(String reference);

    /** POST /checkout/confirm — signature-verified or counter-assisted confirmation. */
    ResponseEntity<Map<String, Object>> confirm(Map<String, Object> requestMap);

    /** POST /checkout/webhook — provider webhook (signature-verified, idempotent). */
    ResponseEntity<String> webhook(String rawBody, String signatureHeader);

    /** POST /checkout/sandbox/settle — TEST settlement (hard-gated to sandbox mode). */
    ResponseEntity<Map<String, Object>> sandboxSettle(Map<String, Object> requestMap);

    /** POST /checkout/cancel — user-cancelled checkout. */
    ResponseEntity<String> cancel(Map<String, Object> requestMap);
}
