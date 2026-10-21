package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.PaymentDao;
import com.inn.cafe.POJO.CardPayment;
import com.inn.cafe.POJO.Payment;
import com.inn.cafe.POJO.PaymentType;
import com.inn.cafe.POJO.UPIPayment;
import com.inn.cafe.Service.PaymentService;
import com.inn.cafe.Utils.PaymentUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Service implementation for handling payment operations (Issues #3 and #4 fixes).
 *
 * verifyUpi    — proper NPCI regex via PaymentUtils + JSON-quote tolerant (the raw
 *                @RequestBody String arrives WITH surrounding quotes for a JSON string
 *                body; the legacy endsWith("@ybl") check therefore never matched and
 *                every UPI id — including valid reused ones — was reported invalid).
 * processPayment — server-authoritative hardening: server generates transaction ids,
 *                stamps timestamps, derives the payment type from the entity class,
 *                validates amount > 0 and UPI/card formats, masks card numbers (only
 *                last 4 digits are persisted).
 * generateQrCode — Issue #4: encodes the SYSTEM-CONFIGURED merchant payee
 *                (cafe.upi.payee-vpa) into a standard UPI intent payload
 *                (upi://pay?pa=..&pn=..&am=..&cu=INR) and renders a self-contained
 *                QR PNG data-URI with ZXing. NO external QR service, NO caller-chosen
 *                payee. Missing/invalid configuration fails safe with a clear error.
 */
@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

    @Autowired
    private PaymentDao paymentDao;

    @Value("${cafe.upi.payee-vpa:}")
    private String payeeVpa;

    @Value("${cafe.upi.payee-name:Cafe Management System}")
    private String payeeName;

    @Value("${cafe.upi.currency:INR}")
    private String currency;

    @Override
    public Payment processPayment(Payment payment) {
        if (payment == null) {
            throw new IllegalArgumentException("Payment payload is required.");
        }
        if (payment.getUserId() == null || payment.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId is required.");
        }
        if (payment.getAmount() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        // server-authoritative fields
        if (payment.getTransactionId() == null || payment.getTransactionId().isBlank()) {
            payment.setTransactionId(PaymentUtils.generateTransactionId());
        }
        payment.setTimestamp(java.time.LocalDateTime.now());

        if (payment instanceof UPIPayment) {
            payment.setPaymentType(PaymentType.UPI);
            if (!PaymentUtils.validateUpiId(payment.getUpiId())) {
                throw new IllegalArgumentException("Invalid UPI ID format: " + payment.getUpiId());
            }
        } else if (payment instanceof CardPayment) {
            payment.setPaymentType(PaymentType.CARD);
            CardPayment card = (CardPayment) payment;
            if (!PaymentUtils.validateCardNumber(card.getCardNumber())) {
                throw new IllegalArgumentException("Invalid card number format (12-19 digits required).");
            }
            card.setCardNumber(PaymentUtils.maskCardNumber(card.getCardNumber())); // store masked only
            card.setCvv(null);
        } else if (payment.getPaymentType() == PaymentType.CARD) {
            // FIX (found in E2E): flat JSON posted to /api/payment/process deserializes as the
            // BASE Payment (Jackson cannot pick a subclass without type info). Convert it to a
            // CardPayment so the discriminator, validation and masking behave consistently —
            // previously card fields were dropped entirely at deserialization time.
            CardPayment card = new CardPayment();
            card.setUserId(payment.getUserId());
            card.setPaymentType(PaymentType.CARD);
            card.setProvider(payment.getProvider());
            card.setTransactionId(payment.getTransactionId());
            card.setAmount(payment.getAmount());
            card.setStatus(payment.isStatus());
            card.setCardNumber(payment.getCardNumber());
            card.setCardHolderName(payment.getCardHolderName());
            card.setCardExpiry(payment.getCardExpiry());
            if (!PaymentUtils.validateCardNumber(card.getCardNumber())) {
                throw new IllegalArgumentException("Invalid card number format (12-19 digits required).");
            }
            card.setCardNumber(PaymentUtils.maskCardNumber(card.getCardNumber())); // store masked only
            card.setCvv(null);
            return paymentDao.save(card);
        } else {
            payment.setPaymentType(PaymentType.WALLET);
        }
        return paymentDao.save(payment);
    }

    @Override
    public List<Payment> getAllPayments() {
        return paymentDao.findAll();
    }

    @Override
    public Payment getPaymentById(Long paymentId) {
        Optional<Payment> payment = paymentDao.findById(paymentId);
        return payment.orElse(null);
    }

    /**
     * Issue #3 fix: format validation with the NPCI regex, tolerant of the JSON string
     * body quoting. Validation is purely FORMAT-based — a previously used (duplicate)
     * valid UPI ID remains valid by design; there is no uniqueness constraint on upi_id.
     */
    @Override
    public boolean verifyUpi(String upiId) {
        if (upiId == null) {
            return false;
        }
        String cleaned = upiId.trim();
        // A JSON string body ("abc@bank") arrives with surrounding quotes — strip them.
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        boolean valid = PaymentUtils.validateUpiId(cleaned);
        log.info("UPI verification for '{}': {}", cleaned, valid ? "valid" : "invalid");
        return valid;
    }

    /**
     * Issue #4 fix: generates a self-contained QR (ZXing, PNG data-URI) encoding a
     * standard UPI intent payload for the CONFIGURED merchant payee. The payee is
     * always taken from server configuration — never from the request — so payments
     * can only ever be directed to the authorized recipient.
     */
    @Override
    public String generateQrCode(String upiId, double amount) {
        if (payeeVpa == null || payeeVpa.isBlank() || !PaymentUtils.validateUpiId(payeeVpa.trim())) {
            log.error("UPI QR requested but cafe.upi.payee-vpa is not configured or invalid — failing safe.");
            throw new IllegalStateException("UPI payee is not configured on the server. Set cafe.upi.payee-vpa.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        try {
            String vpa = payeeVpa.trim();
            String payload = "upi://pay?pa=" + urlEncode(vpa)
                    + "&pn=" + urlEncode(payeeName == null || payeeName.isBlank() ? "Cafe" : payeeName.trim())
                    + "&am=" + String.format(Locale.ROOT, "%.2f", amount)
                    + "&cu=" + (currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(Locale.ROOT))
                    + "&tn=" + urlEncode("Cafe order payment");
            log.info("Generating UPI QR for configured payee '{}' (amount {})", vpa, amount);

            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 300, 300);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
            return dataUri;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("QR generation failed: {}", e.getMessage());
            throw new IllegalStateException("Failed to generate QR code.");
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * Wallet payment redirection contract (unchanged): returns a wallet deep-link URL.
     * NOTE (documented limitation): this is a client-redirect helper; it does not persist
     * a payment record — persistence happens via /api/payment/process.
     */
    @Override
    public String processWalletPayment(String walletType, double amount) {
        if (walletType == null || walletType.isBlank()) {
            throw new IllegalArgumentException("walletType is required.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        String baseUrl;
        switch (walletType.toLowerCase(Locale.ROOT)) {
            case "phonepe":
                baseUrl = "https://www.phonepe.com/pay?amount=" + amount;
                break;
            case "paytm":
                baseUrl = "https://paytm.com/pay?amount=" + amount;
                break;
            case "googlepay":
                baseUrl = "https://gpay.app.goo.gl/?amount=" + amount;
                break;
            default:
                baseUrl = "https://www.wallet.com/pay?amount=" + amount;
                break;
        }
        return baseUrl;
    }
}
