package com.inn.cafe.Service;

import com.inn.cafe.Utils.payments.RazorpayProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RazorpaySignatureTest — REAL HMAC-SHA256 verification vectors (no mocks):
 *  - checkout handler signature = HMAC_SHA256(order_id + "|" + payment_id, key_secret)
 *  - webhook signature           = HMAC_SHA256(raw body, webhook_secret)
 * A tampered payload or signature must NEVER verify.
 */
class RazorpaySignatureTest {

    private RazorpayProvider provider;
    private static final String KEY_ID = "rzp_test_abc123";
    private static final String KEY_SECRET = "supersecret_key_123";
    private static final String WEBHOOK_SECRET = "webhook_secret_456";

    @BeforeEach
    void setUp() {
        provider = new RazorpayProvider();
        ReflectionTestUtils.setField(provider, "keyId", KEY_ID);
        ReflectionTestUtils.setField(provider, "keySecret", KEY_SECRET);
        ReflectionTestUtils.setField(provider, "webhookSecret", WEBHOOK_SECRET);
    }

    private static String hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void validCheckoutSignatureVerifies() throws Exception {
        String signature = hmac("order_123|pay_456", KEY_SECRET);
        assertTrue(provider.verifyCheckoutSignature("order_123", "pay_456", signature));
    }

    @Test
    void tamperedPaymentIdFailsVerification() throws Exception {
        String signature = hmac("order_123|pay_456", KEY_SECRET);
        assertFalse(provider.verifyCheckoutSignature("order_123", "pay_999", signature));
    }

    @Test
    void signatureWithWrongSecretFails() throws Exception {
        String signature = hmac("order_123|pay_456", "other_secret");
        assertFalse(provider.verifyCheckoutSignature("order_123", "pay_456", signature));
    }

    @Test
    void missingFieldsNeverVerify() {
        assertFalse(provider.verifyCheckoutSignature(null, "pay_1", "sig"));
        assertFalse(provider.verifyCheckoutSignature("order_1", null, "sig"));
        assertFalse(provider.verifyCheckoutSignature("order_1", "pay_1", null));
        assertFalse(provider.verifyCheckoutSignature("order_1", "pay_1", ""));
    }

    @Test
    void validWebhookSignatureVerifies() throws Exception {
        String rawBody = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\"}}}}";
        String signature = hmac(rawBody, WEBHOOK_SECRET);
        assertTrue(provider.verifyWebhookSignature(rawBody, signature));
    }

    @Test
    void tamperedWebhookBodyFails() throws Exception {
        String original = "{\"event\":\"payment.captured\",\"amount\":100}";
        String tampered = "{\"event\":\"payment.captured\",\"amount\":999999}";
        String signature = hmac(original, WEBHOOK_SECRET);
        assertFalse(provider.verifyWebhookSignature(tampered, signature));
    }

    @Test
    void webhookWithoutSecretNeverVerifies() {
        RazorpayProvider unconfigured = new RazorpayProvider();
        ReflectionTestUtils.setField(unconfigured, "webhookSecret", "");
        assertFalse(unconfigured.verifyWebhookSignature("{}", "any"));
    }

    @Test
    void configuredReflectsKeyPresence() {
        assertTrue(provider.configured());
        RazorpayProvider unconfigured = new RazorpayProvider();
        ReflectionTestUtils.setField(unconfigured, "keyId", "");
        ReflectionTestUtils.setField(unconfigured, "keySecret", "");
        assertFalse(unconfigured.configured());
    }
}
