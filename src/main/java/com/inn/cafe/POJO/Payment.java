package com.inn.cafe.POJO;

import lombok.Data;
import javax.persistence.*;
// cvv uses javax.persistence.Transient via the wildcard import above
import java.time.LocalDateTime;

/**
 * Represents a payment entity storing transaction details.
 *
 * MAPPING FIX (Issue #3, DEFECT_REGISTER 3.1/3.2/3.3):
 *  - Single-table inheritance: Payment/CardPayment/UPIPayment/WalletPayment all map to the
 *    'payments' table with the default 'dtype' discriminator column. Subclass-specific
 *    columns must therefore be NULLABLE (the legacy nullable=false on card_* made every
 *    UPI/WALLET insert fail with "Field 'card_expiry' doesn't have a default value").
 *  - 'upiId' is declared ONCE here (on the parent). The legacy duplicate field on
 *    UPIPayment shadowed this one in Java while Hibernate persisted the parent's field —
 *    so UPI ids silently never persisted. A payer may pay multiple times with the same
 *    UPI id, hence NO unique constraint on upi_id (duplicate-payment false-invalid fix).
 *  - The 6-arg constructor previously had an EMPTY body; it now initializes the fields
 *    (subclass constructors rely on it).
 */
@Data
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // Unique ID for each payment transaction

    @Column(nullable = false)
    private String userId; // User who made the payment

    @Enumerated(EnumType.STRING)
    private PaymentType paymentType; // Enum (CARD, UPI, WALLET)

    private String provider; // e.g., "VISA", "PhonePe", "Google Pay"

    private String transactionId; // Unique transaction reference

    private double amount; // Payment amount

    private boolean status; // Payment success/failure

    private LocalDateTime timestamp; // When the payment was made

    private String upiId;      // UPI ID for UPI payments (NOT unique — the same payer may pay repeatedly)
    private String walletType; // Wallet Type (Paytm, PhonePe, etc.)

    // FIX (found in E2E): /api/payment/process is declared with the BASE Payment type, so
    // Jackson silently DROPPED card fields on flat JSON — card payments never stored card
    // data (pre-existing defect). The card columns now live here (nullable for non-card
    // rows under single-table inheritance); only the masked PAN is ever persisted.
    @Column(name = "card_number")
    private String cardNumber; // masked version (last 4 digits) is stored by the service layer

    @Column(name = "card_holder_name")
    private String cardHolderName;

    @Column(name = "card_expiry")
    private String cardExpiry;

    @Transient // never persist the CVV
    private String cvv;

    public Payment() {}

    /** FIX (3.3): this constructor previously had an empty body — subclasses calling
     *  super(...) silently lost userId/paymentType/provider/transactionId/amount/status. */
    public Payment(String userId, PaymentType paymentType, String provider, String transactionId, double amount, boolean status, String upiId, String walletType) {
        this.userId = userId;
        this.paymentType = paymentType;
        this.provider = provider;
        this.transactionId = transactionId;
        this.amount = amount;
        this.status = status;
        this.timestamp = LocalDateTime.now();
        this.upiId = upiId;
        this.walletType = walletType;
    }

    public Payment(String userId, PaymentType paymentType, String provider, String transactionId, double amount, boolean status) {
        this(userId, paymentType, provider, transactionId, amount, status, null, null);
    }
}
