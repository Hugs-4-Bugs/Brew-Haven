package com.inn.cafe.Utils.payments;

import org.springframework.stereotype.Component;

/**
 * SANDBOX provider — honest local test mode for development.
 *
 * It performs NO external calls and NEVER fakes a provider verification:
 * settlements in sandbox mode go through /checkout/sandbox/settle, which is
 * hard-gated (HTTP 403) whenever a real provider (RAZORPAY) is active.
 * The UI clearly labels sandbox settlements as TEST payments.
 */
@Component
public class SandboxProvider implements PaymentProvider {

    public static final String ID = "SANDBOX";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public ProviderOrder createOrder(long amountPaise, String currency, String reference) {
        return new ProviderOrder("sbx_order_" + reference,
                "{\"sandbox\":true,\"amount_paise\":" + amountPaise
                        + ",\"currency\":\"" + (currency == null ? "INR" : currency) + "\"}");
    }

    @Override
    public boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature) {
        // Sandbox mode never verifies provider signatures by design — test
        // settlements use the gated /checkout/sandbox/settle endpoint instead.
        return false;
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        return false;
    }
}
