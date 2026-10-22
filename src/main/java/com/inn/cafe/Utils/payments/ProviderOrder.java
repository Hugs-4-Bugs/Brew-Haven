package com.inn.cafe.Utils.payments;

import lombok.Data;

/** Minimal provider-order DTO returned by PaymentProvider.createOrder. */
@Data
public class ProviderOrder {
    private final String providerOrderId;
    private final String rawJson;
}
