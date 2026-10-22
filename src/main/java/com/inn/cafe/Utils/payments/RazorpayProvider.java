package com.inn.cafe.Utils.payments;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

/**
 * Razorpay integration — the payment provider supported by this application's
 * configuration (INR currency, UPI + card rails).
 *
 * ALL secrets come from environment variables and are NEVER committed:
 *   PAYMENT_PROVIDER_KEY            -> key id      (public — safe to hand to the checkout frontend)
 *   PAYMENT_PROVIDER_SECRET         -> key secret  (SERVER ONLY — never sent to the frontend)
 *   PAYMENT_PROVIDER_WEBHOOK_SECRET -> webhook shared secret (HMAC-SHA256 over the raw body)
 *
 * Verification implemented exactly per Razorpay's documented contracts:
 *  - checkout handler: HMAC_SHA256(order_id + "|" + payment_id, key_secret) == razorpay_signature
 *  - webhook:          HMAC_SHA256(raw request body, webhook_secret)      == X-Razorpay-Signature
 * Comparisons are constant-time.
 */
@Slf4j
@Component
public class RazorpayProvider implements PaymentProvider {

    public static final String ID = "RAZORPAY";
    private static final String ORDERS_API = "https://api.razorpay.com/v1/orders";

    @Value("${cafe.payment.razorpay.key-id:}")
    private String keyId;

    @Value("${cafe.payment.razorpay.key-secret:}")
    private String keySecret;

    @Value("${cafe.payment.razorpay.webhook-secret:}")
    private String webhookSecret;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean configured() {
        return keyId != null && !keyId.isBlank()
                && keySecret != null && !keySecret.isBlank();
    }

    /** The PUBLIC key id — the only credential ever exposed to the checkout frontend. */
    public String keyId() {
        return keyId == null ? "" : keyId.trim();
    }

    @Override
    public ProviderOrder createOrder(long amountPaise, String currency, String reference) throws Exception {
        if (!configured()) {
            throw new IllegalStateException(
                    "Razorpay keys are not configured (PAYMENT_PROVIDER_KEY / PAYMENT_PROVIDER_SECRET).");
        }
        JSONObject body = new JSONObject()
                .put("amount", amountPaise)
                .put("currency", currency == null ? "INR" : currency)
                .put("receipt", reference)
                .put("notes", new JSONObject().put("reference", reference));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ORDERS_API))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (keyId() + ":" + keySecret.trim()).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("Razorpay order creation failed HTTP {}: {}", response.statusCode(), response.body());
            throw new IllegalStateException(
                    "Payment provider rejected the order request (HTTP " + response.statusCode() + ").");
        }
        JSONObject json = new JSONObject(response.body());
        String id = json.optString("id");
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Payment provider response did not contain an order id.");
        }
        log.info("Razorpay order {} created for reference {} ({} paise)", id, reference, amountPaise);
        return new ProviderOrder(id, response.body());
    }

    @Override
    public boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature) {
        if (!configured() || anyBlank(providerOrderId, providerPaymentId, signature)) {
            return false;
        }
        String expected = hmacSha256(providerOrderId.trim() + "|" + providerPaymentId.trim(), keySecret.trim());
        return constantTimeEquals(expected, signature.trim());
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()
                || rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String expected = hmacSha256(rawBody, webhookSecret.trim());
        return constantTimeEquals(expected, signatureHeader.trim());
    }

    // ------------------------------------------------------------------ helpers

    static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean anyBlank(String... values) {
        for (String v : values) {
            if (v == null || v.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
