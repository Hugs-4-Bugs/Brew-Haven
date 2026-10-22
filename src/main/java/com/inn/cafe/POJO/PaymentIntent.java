package com.inn.cafe.POJO;

import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * Payment intent — the single authoritative record for ONE checkout attempt.
 *
 * ADDITIVE table (payment_intent). It carries the full, SERVER-COMPUTED pricing
 * snapshot (subtotal / discount / tax / total) and drives the explicit state
 * machine:
 *
 *   CREATED --> PENDING --> SUCCESS   (bill + payment created exactly once)
 *                    |----> FAILED    (provider/webhook reported failure)
 *                    |----> CANCELLED (user closed checkout)
 *                    |----> EXPIRED   (lazily transitioned past expiresAt)
 *
 * Money integrity guarantees:
 *  - the payable amount is ALWAYS computed by PricingService from live DB
 *    product prices — the client never supplies any amount;
 *  - reference is UNIQUE — one reference == one payment request, forever;
 *  - (user_id, client_key) is UNIQUE — retries / double clicks / refreshes
 *    with the same idempotency key reuse the SAME intent (no duplicates);
 *  - bill_id is UNIQUE — a single intent can never produce two bills.
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "payment_intent",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_intent_reference", columnNames = "reference"),
                @UniqueConstraint(name = "uk_payment_intent_client_key", columnNames = {"user_id", "client_key"}),
                @UniqueConstraint(name = "uk_payment_intent_bill", columnNames = "bill_id")
        })
public class PaymentIntent implements Serializable {
    private static final long serialVersionUID = 90210L;

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String METHOD_UPI = "UPI";
    public static final String METHOD_CARD = "CARD";
    public static final String METHOD_CASH = "CASH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Server-generated unique payment reference (PAY-XXXXXXXXXXXXXXXX). */
    @Column(name = "reference", nullable = false, length = 40)
    private String reference;

    /** Client-supplied idempotency key — (userId, clientKey) is UNIQUE. */
    @Column(name = "client_key", nullable = false, length = 150)
    private String clientKey;

    /** Authenticated user (email) that created the intent. */
    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    /** CREATED | PENDING | SUCCESS | FAILED | CANCELLED | EXPIRED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** UPI | CARD | CASH. */
    @Column(name = "method", nullable = false, length = 10)
    private String method;

    /** The single coupon applied to this order (1:1 — see coupon rules). Nullable. */
    @Column(name = "coupon_code", length = 40)
    private String couponCode;

    /** SERVER-computed amounts, whole rupees (single source of truth). */
    @Column(name = "subtotal")
    private Integer subtotal;

    @Column(name = "discount")
    private Integer discount;

    @Column(name = "tax")
    private Integer tax;

    @Column(name = "total")
    private Integer total;

    /** Server-verified product snapshot (real DB prices) in the bill productDetails JSON shape. */
    @Lob
    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;

    @Column(name = "customer_name", length = 255)
    private String customerName;

    @Column(name = "contact_number", length = 255)
    private String contactNumber;

    @Column(name = "email", length = 255)
    private String email;

    /** RAZORPAY | SANDBOX | COUNTER. */
    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "provider_order_id", length = 80)
    private String providerOrderId;

    @Column(name = "provider_payment_id", length = 80)
    private String providerPaymentId;

    /** The bill created on verified SUCCESS — UNIQUE (one intent, at most one bill). */
    @Column(name = "bill_id")
    private Integer billId;

    @Column(name = "payment_id")
    private Long paymentId;

    /** Customer UPI id for assisted UPI confirmation (POS), never a payee. */
    @Column(name = "upi_id", length = 255)
    private String upiId;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at")
    private Date createdAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at")
    private Date updatedAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "expires_at")
    private Date expiresAt;
}
