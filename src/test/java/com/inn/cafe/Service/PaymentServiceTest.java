package com.inn.cafe.Service;

import com.inn.cafe.DAO.PaymentDao;
import com.inn.cafe.POJO.CardPayment;
import com.inn.cafe.POJO.Payment;
import com.inn.cafe.POJO.PaymentType;
import com.inn.cafe.POJO.UPIPayment;
import com.inn.cafe.ServiceImpl.PaymentServiceImpl;
import com.inn.cafe.Utils.PaymentUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PaymentServiceTest — regression coverage for Issue #3 and Issue #4 fixes
 * (the legacy test class was completely empty, see DEFECT_REGISTER T.3).
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @Mock
    private PaymentDao paymentDao;

    @BeforeEach
    void configurePayee() {
        // Issue #4: configured merchant payee
        ReflectionTestUtils.setField(paymentService, "payeeVpa", "cafe@upi");
        ReflectionTestUtils.setField(paymentService, "payeeName", "Test Cafe");
        ReflectionTestUtils.setField(paymentService, "currency", "INR");
    }

    // ---------------------------------------------------------------- verifyUpi (Issue #3)

    @Test
    void verifyUpi_acceptsValidNpciFormat() {
        assertTrue(paymentService.verifyUpi("prabhat@okhdfc"));   // previously ALWAYS false
        assertTrue(paymentService.verifyUpi("cafe@ybl"));
        assertTrue(paymentService.verifyUpi("shop123@paytm"));
        assertTrue(paymentService.verifyUpi("a.b-c_d@axisbank"));
    }

    @Test
    void verifyUpi_isTolerantOfJsonStringQuotes() {
        // the raw @RequestBody String for a JSON string body arrives WITH quotes
        assertTrue(paymentService.verifyUpi("\"prabhat@okhdfc\""));
    }

    @Test
    void verifyUpi_rejectsInvalidFormats() {
        assertFalse(paymentService.verifyUpi("no-at-sign"));
        assertFalse(paymentService.verifyUpi("@ybl"));            // missing local part
        assertFalse(paymentService.verifyUpi("prabhat@"));        // missing handle
        assertFalse(paymentService.verifyUpi("bad handle@ybl"));  // space in local part
        assertFalse(paymentService.verifyUpi(null));
        assertFalse(paymentService.verifyUpi(""));
    }

    @Test
    void verifyUpi_validIdRemainsValidOnReuse() {
        // Issue #3 core regression: the SAME valid UPI id must validate every time
        // (duplicate payment with a previously used id must not report invalid)
        for (int i = 0; i < 5; i++) {
            assertTrue(paymentService.verifyUpi("prabhat@okhdfc"));
        }
    }

    // ---------------------------------------------------------------- processPayment (Issue #3)

    @Test
    void processPayment_upi_persistsUpiIdAndServerFields() {
        UPIPayment upi = new UPIPayment();
        upi.setUserId("user@cafe.local");
        upi.setUpiId("prabhat@okhdfc");
        upi.setAmount(250.0);
        upi.setStatus(true);
        when(paymentDao.save(any(UPIPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment saved = paymentService.processPayment(upi);

        assertEquals(PaymentType.UPI, saved.getPaymentType());
        assertEquals("prabhat@okhdfc", saved.getUpiId());      // FIX 3.1: upi id persists
        assertNotNull(saved.getTransactionId());                 // FIX 3.7: server-generated
        assertTrue(saved.getTransactionId().startsWith("TXN-"));
        assertNotNull(saved.getTimestamp());                     // FIX 3.7: server-stamped
    }

    @Test
    void processPayment_upi_rejectsInvalidUpiId() {
        UPIPayment upi = new UPIPayment();
        upi.setUserId("user@cafe.local");
        upi.setUpiId("not-a-upi-id");
        upi.setAmount(100);
        assertThrows(IllegalArgumentException.class, () -> paymentService.processPayment(upi));
    }

    @Test
    void processPayment_rejectsNonPositiveAmount() {
        UPIPayment upi = new UPIPayment();
        upi.setUserId("user@cafe.local");
        upi.setUpiId("prabhat@okhdfc");
        upi.setAmount(0);
        assertThrows(IllegalArgumentException.class, () -> paymentService.processPayment(upi));
    }

    @Test
    void processPayment_card_masksCardNumber() {
        CardPayment card = new CardPayment();
        card.setUserId("user@cafe.local");
        card.setAmount(500);
        card.setCardNumber("4111111111111111");
        card.setCardHolderName("Test User");
        card.setCardExpiry("12/28");
        card.setCvv("123");
        when(paymentDao.save(any(CardPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment saved = paymentService.processPayment(card);

        assertEquals(PaymentType.CARD, saved.getPaymentType());
        assertEquals("****-****-****-1111", ((CardPayment) saved).getCardNumber()); // masked
    }

    @Test
    void processPayment_rejectsBadCardNumber() {
        CardPayment card = new CardPayment();
        card.setUserId("user@cafe.local");
        card.setAmount(500);
        card.setCardNumber("12"); // too short
        assertThrows(IllegalArgumentException.class, () -> paymentService.processPayment(card));
    }

    @Test
    void processPayment_requiresUserId() {
        UPIPayment upi = new UPIPayment();
        upi.setUpiId("prabhat@okhdfc");
        upi.setAmount(100);
        assertThrows(IllegalArgumentException.class, () -> paymentService.processPayment(upi));
    }

    // ---------------------------------------------------------------- generateQrCode (Issue #4)

    @Test
    void generateQrCode_returnsSelfContainedDataUri() {
        String qr = paymentService.generateQrCode("ignored@whatever", 250.0);
        assertNotNull(qr);
        assertTrue(qr.startsWith("data:image/png;base64,"));  // self-contained, NOT an external URL
        assertFalse(qr.contains("qrserver.com"));             // no third-party QR service
        assertTrue(qr.length() > 200);                        // an actual PNG payload
    }

    @Test
    void generateQrCode_usesConfiguredPayee_notRequestUpiId() {
        paymentService.generateQrCode("attacker@evil", 99.0);
        // payload correctness is verified in PaymentUtilsTest/decoding E2E; here we assert
        // the payee config is read (no exception, deterministic size for same amount)
        String qr1 = paymentService.generateQrCode("a@b", 100.0);
        String qr2 = paymentService.generateQrCode("c@d", 100.0);
        assertEquals(qr1, qr2); // identical amount+config => identical QR regardless of passed upiId
    }

    @Test
    void generateQrCode_failsSafeWhenPayeeNotConfigured() {
        ReflectionTestUtils.setField(paymentService, "payeeVpa", "");
        assertThrows(IllegalStateException.class, () -> paymentService.generateQrCode("x@y", 100.0));
    }

    @Test
    void generateQrCode_failsSafeWhenPayeeInvalid() {
        ReflectionTestUtils.setField(paymentService, "payeeVpa", "not a vpa");
        assertThrows(IllegalStateException.class, () -> paymentService.generateQrCode("x@y", 100.0));
    }

    @Test
    void generateQrCode_rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> paymentService.generateQrCode("x@y", 0));
        assertThrows(IllegalArgumentException.class, () -> paymentService.generateQrCode("x@y", -5));
    }

    // ---------------------------------------------------------------- wallet

    @Test
    void walletPayment_requiresWalletTypeAndPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> paymentService.processWalletPayment(null, 100));
        assertThrows(IllegalArgumentException.class, () -> paymentService.processWalletPayment("paytm", 0));
        assertEquals("https://paytm.com/pay?amount=50.0", paymentService.processWalletPayment("paytm", 50));
    }

    // ---------------------------------------------------------------- utils

    @Test
    void paymentUtils_regexAndMasking() {
        assertTrue(PaymentUtils.validateUpiId("user@okicici"));
        assertFalse(PaymentUtils.validateUpiId("user@ok icici"));
        assertEquals("****-****-****-1111", PaymentUtils.maskCardNumber("4111111111111111"));
        assertEquals("****", PaymentUtils.maskCardNumber("123"));
        assertTrue(PaymentUtils.generateTransactionId().startsWith("TXN-"));
    }

    @Test
    void processPayment_flatCardJson_convertsToCardPaymentAndMasks() {
        // /api/payment/process posts flat JSON -> Jackson gives the BASE Payment with
        // paymentType=CARD; the service must convert, validate and mask (E2E regression:
        // card fields were previously dropped entirely and the row saved as base Payment).
        Payment flat = new Payment();
        flat.setUserId("user@cafe.local");
        flat.setPaymentType(PaymentType.CARD);
        flat.setAmount(500);
        flat.setCardNumber("4111111111111111");
        flat.setCardHolderName("Test User");
        flat.setCardExpiry("12/28");
        flat.setCvv("123");
        when(paymentDao.save(any(CardPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment saved = paymentService.processPayment(flat);

        assertTrue(saved instanceof CardPayment);
        assertEquals(PaymentType.CARD, saved.getPaymentType());
        assertEquals("****-****-****-1111", saved.getCardNumber()); // masked
        assertNull(saved.getCvv());                                  // never persisted
    }
}
