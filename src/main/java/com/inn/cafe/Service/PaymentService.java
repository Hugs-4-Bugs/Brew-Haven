package com.inn.cafe.Service;

import com.inn.cafe.POJO.Payment;
import java.util.List;

/**
 * Service interface for handling payment-related operations.
 * This layer is responsible for defining the business logic.
 */
public interface PaymentService {

    /**
     * Process a new payment.
     * @param payment The payment details provided by the user.
     * @return Payment object after successful transaction.
     */
    Payment processPayment(Payment payment);

    /**
     * Retrieve all payment transactions.
     * @return List of all payments.
     */
    List<Payment> getAllPayments();

    /**
     * Retrieve a payment by its ID.
     * @param paymentId Unique identifier of the payment.
     * @return Payment object if found, otherwise null.
     */
    Payment getPaymentById(Long paymentId);

    /**
     * Verify if the provided UPI ID is valid.
     * @param upiId The UPI ID to be verified.
     * @return true if the UPI ID is valid, false otherwise.
     */
    boolean verifyUpi(String upiId);

    /**
     * Generate a QR code for UPI payments.
     * The QR code will contain the UPI ID and amount for easy payments.
     * @param upiId The UPI ID of the payee.
     * @param amount The transaction amount.
     * @return A URL containing the generated QR code.
     */
    String generateQrCode(String upiId, double amount);

    /**
     * ADDITIVE: QR for the checkout flow — encodes the SERVER-CONFIGURED
     * merchant payee and a SERVER-COMPUTED amount (from the payment intent),
     * with the payment reference embedded in the note for reconciliation.
     */
    String generateQrCode(double amount, String note);

    /**
     * Handle wallet-based payments and return a redirection link.
     * The method will generate a URL for payment redirection.
     * @param walletType The type of wallet (e.g., PhonePe, Paytm, Google Pay).
     * @param amount The transaction amount.
     * @return A URL to redirect the user to the respective wallet app.
     */
    String processWalletPayment(String walletType, double amount);


}
