package com.inn.cafe.Rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ADDITIVE checkout endpoints (see ServiceImpl/CheckoutServiceImpl).
 *
 * /checkout/webhook is PUBLIC (provider servers cannot carry our JWT) — its
 * authenticity is enforced by HMAC signature verification, not by login.
 */
@RequestMapping(path = "/checkout")
public interface CheckoutRest {

    /** Authoritative pricing + coupon validation. Body: {items:[{productId,quantity}], couponCode?, email?}. */
    @PostMapping(path = "/price")
    ResponseEntity<Map<String, Object>> price(@RequestBody Map<String, Object> requestMap);

    /** Idempotent payment-intent creation. Body: {items, method: UPI|CARD|CASH, clientKey, name, contactNumber, email, couponCode?, upiId?}. */
    @PostMapping(path = "/intent")
    ResponseEntity<Map<String, Object>> createIntent(@RequestBody Map<String, Object> requestMap);

    /** Status polling for the QR / waiting screens. */
    @GetMapping(path = "/intent/{reference}")
    ResponseEntity<Map<String, Object>> intentStatus(@PathVariable("reference") String reference);

    /** UPI QR (server-configured payee, SERVER-COMPUTED amount) as a PNG data-URI. */
    @GetMapping(path = "/qr/{reference}")
    ResponseEntity<String> intentQr(@PathVariable("reference") String reference);

    /** Signature-verified (or counter-assisted) confirmation. Body: {reference, providerOrderId?, providerPaymentId?, signature?, assisted?}. */
    @PostMapping(path = "/confirm")
    ResponseEntity<Map<String, Object>> confirm(@RequestBody Map<String, Object> requestMap);

    /** Provider webhook — HMAC-verified, idempotent; NO authentication by design. */
    @PostMapping(path = "/webhook")
    ResponseEntity<String> webhook(@RequestHeader(value = "X-Razorpay-Signature", required = false) String signatureHeader,
                                   @RequestBody String rawBody);

    /** TEST settlement — hard-gated to sandbox mode (403 when a real provider is active). */
    @PostMapping(path = "/sandbox/settle")
    ResponseEntity<Map<String, Object>> sandboxSettle(@RequestBody Map<String, Object> requestMap);

    /** User-cancelled checkout. Body: {reference}. */
    @PostMapping(path = "/cancel")
    ResponseEntity<String> cancel(@RequestBody Map<String, Object> requestMap);
}
