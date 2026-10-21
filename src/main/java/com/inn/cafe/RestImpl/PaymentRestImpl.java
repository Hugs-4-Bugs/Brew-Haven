package com.inn.cafe.RestImpl;

import com.inn.cafe.Constents.CafeConstants;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Payment;
import com.inn.cafe.POJO.UPIPayment;
import com.inn.cafe.Rest.PaymentRest;
import com.inn.cafe.Service.PaymentService;
import com.inn.cafe.Utils.PaymentUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Implementation of PaymentRest interface (Issues #3/#4 fixes).
 *
 * Response semantics (documented contract):
 *  - IllegalArgumentException  -> 400 with {"message": ...} (invalid client input)
 *  - IllegalStateException     -> 503 with {"message": ...} (server misconfiguration — QR fails safe)
 *  - Persistence failures      -> 500 {"message":"Something Went Wrong"}
 */
@Slf4j
@RestController
@RequestMapping("/api/payment") // Base path for all payment-related APIs
@CrossOrigin(origins = "*") // Allow requests from any frontend (same as baseline; tighten via reverse proxy in prod)
public class PaymentRestImpl implements PaymentRest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JwtFilter jwtFilter;

    @Override
    public ResponseEntity<Payment> processPayment(@RequestBody Payment payment) {
        try {
            Payment saved = paymentService.processPayment(payment);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return paymentError(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("processPayment failed: {}", e.getMessage());
            return paymentError(HttpStatus.INTERNAL_SERVER_ERROR, "Something Went Wrong");
        }
    }

    /**
     * SECURITY FIX: payment records are admin-only. Any authenticated user could
     * previously enumerate every user's payment history (IDOR-class exposure).
     */
    @Override
    public ResponseEntity<List<Payment>> getAllPayments() {
        try {
            if (!jwtFilter.isAdmin()) {
                return paymentError(HttpStatus.UNAUTHORIZED, CafeConstants.UNAUTHOROZED_ACCESS) == null
                        ? null
                        : ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(java.util.Collections.<Payment>emptyList());
            }
            List<Payment> payments = paymentService.getAllPayments();
            // DEFECT_REGISTER 3.8 fix: never expose full card numbers in list responses.
            payments.forEach(PaymentRestImpl::maskSensitive);
            return ResponseEntity.ok(payments);
        } catch (Exception e) {
            log.error("getAllPayments failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** SECURITY FIX: individual payment lookup is admin-only (same rationale as getAllPayments). */
    @Override
    public ResponseEntity<Payment> getPaymentById(@PathVariable("id") Long paymentId) {
        try {
            if (!jwtFilter.isAdmin()) {
                return paymentError(HttpStatus.UNAUTHORIZED, CafeConstants.UNAUTHOROZED_ACCESS);
            }
            Payment payment = paymentService.getPaymentById(paymentId);
            if (payment == null) {
                return ResponseEntity.notFound().build();
            }
            maskSensitive(payment);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            log.error("getPaymentById failed: {}", e.getMessage());
            return paymentError(HttpStatus.INTERNAL_SERVER_ERROR, "Something Went Wrong");
        }
    }

    @Override
    public ResponseEntity<String> verifyUpi(@RequestBody String upiId) {
        try {
            boolean isValid = paymentService.verifyUpi(upiId);
            return isValid ? ResponseEntity.ok("UPI ID is valid") : badRequestPlain("Invalid UPI ID");
        } catch (Exception e) {
            log.error("verifyUpi failed: {}", e.getMessage());
            return internalError();
        }
    }

    @Override
    public ResponseEntity<String> processUpiPayment(@RequestBody UPIPayment payment) {
        try {
            UPIPayment saved = (UPIPayment) paymentService.processPayment(payment);
            return ResponseEntity.ok("UPI Payment stored successfully! Transaction ID: " + saved.getTransactionId());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (ClassCastException e) {
            return internalError();
        } catch (Exception e) {
            log.error("processUpiPayment failed: {}", e.getMessage());
            return internalError();
        }
    }

    @Override
    public ResponseEntity<String> processQrUpiPayment(@RequestBody Payment payment) {
        try {
            // Issue #4: the QR always encodes the SERVER-CONFIGURED merchant payee;
            // the request's upiId is not used as the payee (backend-authoritative).
            String qrDataUri = paymentService.generateQrCode(payment.getUpiId(), payment.getAmount());
            return ResponseEntity.ok(qrDataUri);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return serviceUnavailable(e.getMessage());
        } catch (Exception e) {
            log.error("processQrUpiPayment failed: {}", e.getMessage());
            return internalError();
        }
    }

    @Override
    public ResponseEntity<String> processWalletPayment(@RequestBody Payment payment) {
        try {
            String redirectionUrl = paymentService.processWalletPayment(payment.getWalletType(), payment.getAmount());
            return ResponseEntity.ok(redirectionUrl);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("processWalletPayment failed: {}", e.getMessage());
            return internalError();
        }
    }

    @Override
    public ResponseEntity<String> generateQrCode(@PathVariable String upiId, @PathVariable double amount) {
        try {
            String qrDataUri = paymentService.generateQrCode(upiId, amount);
            return ResponseEntity.ok(qrDataUri);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return serviceUnavailable(e.getMessage());
        } catch (Exception e) {
            log.error("generateQrCode failed: {}", e.getMessage());
            return internalError();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Defensive masking for anything that slipped past the service layer. */
    private static void maskSensitive(Payment p) {
        if (p instanceof com.inn.cafe.POJO.CardPayment) {
            com.inn.cafe.POJO.CardPayment card = (com.inn.cafe.POJO.CardPayment) p;
            card.setCardNumber(PaymentUtils.maskCardNumber(card.getCardNumber()));
            card.setCvv(null);
        }
    }

    private static ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.badRequest().body("{\"message\":\"" + message + "\"}");
    }

    private static ResponseEntity<String> badRequestPlain(String message) {
        return ResponseEntity.badRequest().body(message);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Payment> paymentError(HttpStatus status, String message) {
        // Typed-erasure bridge: the endpoint contract returns Payment, but validation
        // failures carry a JSON message body (Jackson serializes the actual body object).
        return (ResponseEntity) ResponseEntity.status(status).body("{\"message\":\"" + message + "\"}");
    }

    private static ResponseEntity<String> serviceUnavailable(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("{\"message\":\"" + message + "\"}");
    }

    private static ResponseEntity<String> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"message\":\"Something Went Wrong\"}");
    }
}
