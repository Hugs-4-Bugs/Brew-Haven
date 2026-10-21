package com.inn.cafe.Utils;

import java.util.UUID;

/**
 * Utility class for payment-related operations.
 */
public class PaymentUtils {

    /**
     * Generates a unique transaction ID.
     * @return Random UUID as a string.
     */
    public static String generateTransactionId() {
        return "TXN-" + UUID.randomUUID().toString();
    }

    /**
     * Validates a UPI ID (VPA) format per the NPCI pattern:
     * 2-256 chars of [a-zA-Z0-9.\-_] followed by '@' and a 2-64 char alphanumeric handle.
     * Examples: prabhat@okhdfc, cafe@ybl, shop123@paytm.
     * A UPI id is REUSABLE — validity does not depend on whether it paid before.
     */
    public static boolean validateUpiId(String upiId) {
        return upiId != null && upiId.matches("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z0-9]{2,64}$");
    }

    /**
     * Validates card number format (ISO/IEC 7812: 12-19 digits).
     * @param cardNumber The card number as a string.
     * @return true if valid, false otherwise.
     */
    public static boolean validateCardNumber(String cardNumber) {
        return cardNumber != null && cardNumber.matches("^[0-9]{12,19}$");
    }

    /**
     * Masks a card number, keeping only the last 4 digits (PCI-DSS style).
     * Example: 4111111111111111 -> ****-****-****-1111
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        String last4 = cardNumber.substring(cardNumber.length() - 4);
        return "****-****-****-" + last4;
    }
}
