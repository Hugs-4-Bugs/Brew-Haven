package com.inn.cafe.POJO;

import lombok.Data;
import lombok.EqualsAndHashCode;
import javax.persistence.*;

/**
 * Represents a payment made using a credit/debit card.
 *
 * FIX (Issue #3 + E2E): card columns are nullable and now declared ONCE on the parent
 * (Payment) — this subclass previously redeclared them with nullable=false, which (a)
 * broke UPI/WALLET inserts under single-table inheritance and (b) shadowed the parent
 * mapping. The class remains as the discriminator subtype for typed endpoints.
 * CVV is transient and never persisted.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue("CARD") // Discriminator value for Card payments
public class CardPayment extends Payment {
    // all fields inherited from Payment (cardNumber, cardHolderName, cardExpiry, cvv)
}
