package com.inn.cafe.Utils.payments;

/**
 * Payment provider adapter contract.
 *
 * Two implementations exist:
 *  - RazorpayProvider — the real provider (UPI + cards, INR). Requires
 *    PAYMENT_PROVIDER_KEY / PAYMENT_PROVIDER_SECRET (+ PAYMENT_PROVIDER_WEBHOOK_SECRET
 *    for webhook verification). Makes REAL HTTP calls to api.razorpay.com.
 *  - SandboxProvider  — honest local test mode for development without
 *    provider secrets. Settlements happen through the explicitly gated
 *    /checkout/sandbox/settle endpoint and are clearly labelled TEST.
 *
 * NOTHING in this system ever marks a payment successful without either
 * (a) a provider-verified signature (checkout handler or webhook), or
 * (b) an explicit operator attestation (counter-assisted flow).
 */
public interface PaymentProvider {

    String id();

    /** True when the provider is fully configured with real credentials. */
    boolean configured();

    /**
     * Creates a provider order for the EXACT amount (paise, smallest currency unit).
     * The amount always comes from the server-side pricing engine — never the client.
     */
    ProviderOrder createOrder(long amountPaise, String currency, String reference) throws Exception;

    /** Verifies the checkout handler signature (Razorpay: HMAC_SHA256(order|payment, key_secret)). */
    boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature);

    /** Verifies the webhook signature (Razorpay: HMAC_SHA256(rawBody, webhook_secret)). */
    boolean verifyWebhookSignature(String rawBody, String signatureHeader);
}
