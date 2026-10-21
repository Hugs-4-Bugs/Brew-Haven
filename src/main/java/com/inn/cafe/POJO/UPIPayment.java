package com.inn.cafe.POJO;

import lombok.Data;
import lombok.EqualsAndHashCode;
import javax.persistence.*;

/**
 * Represents a UPI-based payment transaction.
 *
 * FIX (Issue #3, DEFECT_REGISTER 3.1/3.6): the class-level 'upiId' field was REMOVED.
 * It previously shadowed Payment.upiId in Java while Hibernate persisted only the
 * parent's field — UPI ids silently never persisted — and its intended unique=true
 * constraint would also have rejected a second payment from the same payer
 * (duplicate-payment false-invalid). The parent field is the single mapping now.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue("UPI") // Discriminator value for UPI payments
public class UPIPayment extends Payment {

    private String qrCode; // QR Code reference for payment verification

    public UPIPayment() {
        super();
    }

    public UPIPayment(String userId, String provider, String transactionId, double amount, boolean status, String upiId, String qrCode) {
        super(userId, PaymentType.UPI, provider, transactionId, amount, status, upiId, null);
        this.qrCode = qrCode;
    }
}
