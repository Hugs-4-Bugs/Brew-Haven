package com.inn.cafe.Service;

import java.util.Map;

/**
 * Transactional settlement core — transitions a payment intent to SUCCESS and
 * creates the bill (+ payment record for money-claiming methods) EXACTLY once.
 *
 * Kept in its own bean so the Spring @Transactional proxy applies regardless
 * of which entry point reaches it: REST confirm, provider webhook, or the
 * gated sandbox settlement endpoint. All paths converge on the same guarded
 * state transition, so duplicate submissions, retries and racing webhooks can
 * never produce a second bill or payment.
 */
public interface CheckoutSettlementService {

    /**
     * Settles an intent as SUCCESS (idempotent).
     *
     * @param reference        the payment intent reference
     * @param providerPaymentId provider transaction id (null for counter-cash:
     *                         no payment record is created — cash is not
     *                         provider-verified money)
     * @return receipt body (reference, billUuid, transactionId, amounts, ...)
     */
    Map<String, Object> settleSuccess(String reference, String providerPaymentId);
}
