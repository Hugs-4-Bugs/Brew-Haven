package com.inn.cafe.Service;

import com.inn.cafe.DAO.PaymentDao;
import com.inn.cafe.DAO.PaymentIntentDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.Payment;
import com.inn.cafe.POJO.PaymentIntent;
import com.inn.cafe.POJO.UPIPayment;
import com.inn.cafe.ServiceImpl.CheckoutServiceImpl;
import com.inn.cafe.ServiceImpl.CheckoutSettlementServiceImpl;
import com.inn.cafe.Utils.payments.RazorpayProvider;
import com.inn.cafe.Utils.payments.SandboxProvider;
import com.inn.cafe.Wrapper.PricingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CheckoutFlowTest — payment intents, idempotency, the state machine, webhook
 * handling and the settlement core. The invariant under test everywhere:
 * ONE successful payment intent produces EXACTLY ONE bill and ONE payment,
 * no matter how many times it is confirmed, retried or re-delivered.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutFlowTest {

    @InjectMocks
    private CheckoutServiceImpl checkoutService;

    @Mock
    private PaymentIntentDao intentDao;

    @Mock
    private PaymentDao paymentDao;

    @Mock
    private PricingService pricingService;

    @Mock
    private BillService billService;

    @Mock
    private com.inn.cafe.Service.CheckoutSettlementService settlementService;

    @Mock
    private com.inn.cafe.Service.PaymentService paymentService;

    @Mock
    private JwtFilter jwtFilter;

    @Mock
    private RazorpayProvider razorpayProvider;

    @Mock
    private SandboxProvider sandboxProvider;

    // Settlement core is a separate bean — test it with its own mocks.
    @InjectMocks
    private CheckoutSettlementServiceImpl settlement;

    @BeforeEach
    void resolveSandboxProvider() {
        lenient().when(razorpayProvider.configured()).thenReturn(false);
        lenient().when(sandboxProvider.configured()).thenReturn(true);
        lenient().when(sandboxProvider.id()).thenReturn(SandboxProvider.ID);
        ReflectionTestUtils.setField(checkoutService, "providerSetting", "AUTO");
        ReflectionTestUtils.setField(checkoutService, "expiryMinutes", 15);
        checkoutService.resolveProvider();
    }

    /** Switches the service under test into real-provider (RAZORPAY) mode. */
    private void activateRazorpay() {
        lenient().when(razorpayProvider.configured()).thenReturn(true);
        lenient().when(razorpayProvider.id()).thenReturn("RAZORPAY");
        ReflectionTestUtils.setField(checkoutService, "providerSetting", "RAZORPAY");
        checkoutService.resolveProvider();
    }

    // ---------------------------------------------------------------- fixtures

    private static Map<String, Object> item(int productId, int quantity) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", productId);
        m.put("quantity", quantity);
        return m;
    }

    private static PricingResult pricing(int subtotal, int discount, int tax) {
        PricingResult r = new PricingResult();
        r.setSubtotal(subtotal);
        r.setDiscount(discount);
        r.setTax(tax);
        r.setTotal(subtotal - discount + tax);
        r.setTotalPaise(r.getTotal() * 100L);
        r.setCurrency("INR");
        r.setCouponApplied(discount > 0);
        r.setCouponCode(discount > 0 ? "BREW3" : null);
        r.setItems(new ArrayList<>());
        return r;
    }

    private static PaymentIntent pendingIntent(String reference, String method) {
        PaymentIntent i = new PaymentIntent();
        i.setId(1L);
        i.setReference(reference);
        i.setClientKey("key-1");
        i.setUserId("customer@x.io");
        i.setStatus(PaymentIntent.STATUS_PENDING);
        i.setMethod(method);
        i.setCouponCode("BREW3");
        i.setSubtotal(500);
        i.setDiscount(50);
        i.setTax(0);
        i.setTotal(450);
        i.setItemsJson("[]");
        i.setCustomerName("Customer");
        i.setContactNumber("9999999999");
        i.setEmail("customer@x.io");
        i.setProvider("SANDBOX");
        i.setProviderOrderId("order_1");
        i.setCreatedAt(new java.util.Date());
        i.setUpdatedAt(new java.util.Date());
        i.setExpiresAt(new java.util.Date(System.currentTimeMillis() + 600_000));
        return i;
    }

    private static Map<String, Object> requestBody(String method, String clientKey, String coupon) {
        Map<String, Object> body = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(1, 2));
        body.put("items", items);
        body.put("method", method);
        body.put("clientKey", clientKey);
        body.put("name", "Customer");
        body.put("contactNumber", "9999999999");
        body.put("email", "customer@x.io");
        if (coupon != null) {
            body.put("couponCode", coupon);
        }
        return body;
    }

    // ---------------------------------------------------------------- createIntent — idempotency + server pricing

    @Test
    void createIntentIsIdempotentPerClientKey() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        PaymentIntent existing = pendingIntent("PAY-EXISTING", "UPI");
        when(intentDao.findByUserIdAndClientKey("customer@x.io", "key-1")).thenReturn(existing);

        ResponseEntity<Map<String, Object>> response =
                checkoutService.createIntent(requestBody("UPI", "key-1", "BREW3"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PAY-EXISTING", response.getBody().get("reference"));
        verify(intentDao, never()).save(any(PaymentIntent.class)); // NO duplicate intent
    }

    @Test
    void createIntentReturnsReceiptWhenAlreadySuccessful() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        PaymentIntent done = pendingIntent("PAY-DONE", "CASH");
        done.setStatus(PaymentIntent.STATUS_SUCCESS);
        done.setBillId(42);
        when(intentDao.findByUserIdAndClientKey("customer@x.io", "key-1")).thenReturn(done);
        Bill bill = new Bill();
        bill.setId(42);
        bill.setUuid("BILL-1");
        when(billService.getBillById(42)).thenReturn(bill);

        ResponseEntity<Map<String, Object>> response =
                checkoutService.createIntent(requestBody("CASH", "key-1", "BREW3"));

        assertEquals(Boolean.TRUE, response.getBody().get("alreadyCompleted"));
        assertEquals("BILL-1", response.getBody().get("billUuid"));
    }

    @Test
    void createIntentRejectsFailedAttemptReuse() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        PaymentIntent failed = pendingIntent("PAY-FAIL", "UPI");
        failed.setStatus(PaymentIntent.STATUS_FAILED);
        when(intentDao.findByUserIdAndClientKey("customer@x.io", "key-1")).thenReturn(failed);

        ResponseEntity<Map<String, Object>> response =
                checkoutService.createIntent(requestBody("UPI", "key-1", "BREW3"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void createIntentUsesServerPricingNeverClientAmounts() throws Exception {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByUserIdAndClientKey(anyString(), anyString())).thenReturn(null);
        when(pricingService.price(any(), eq("BREW3"), eq("customer@x.io"))).thenReturn(pricing(500, 50, 0));
        when(sandboxProvider.createOrder(eq(45000L), eq("INR"), anyString()))
                .thenReturn(new com.inn.cafe.Utils.payments.ProviderOrder("sbx_order_1", "{}"));
        when(intentDao.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response =
                checkoutService.createIntent(requestBody("UPI", "key-1", "BREW3"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<PaymentIntent> captor = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(intentDao).save(captor.capture());
        PaymentIntent saved = captor.getValue();
        assertEquals(500, saved.getSubtotal());
        assertEquals(50, saved.getDiscount());
        assertEquals(450, saved.getTotal());
        assertEquals("BREW3", saved.getCouponCode());
        assertEquals(PaymentIntent.STATUS_PENDING, saved.getStatus());

        // The provider order was created for the EXACT paise amount.
        verify(sandboxProvider).createOrder(eq(45000L), eq("INR"), anyString());

        // The request body carries NO amount field at all — the API cannot even receive one.
        assertEquals("45000", String.valueOf(response.getBody().get("amountPaise")));
    }

    @Test
    void createIntentRejectsCouponThatFailsValidation() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByUserIdAndClientKey(anyString(), anyString())).thenReturn(null);
        PricingResult rejected = pricing(500, 0, 0);
        rejected.setCouponApplied(false);
        rejected.setCouponRejectionReason("This coupon has expired.");
        when(pricingService.price(any(), eq("BREW3"), eq("customer@x.io"))).thenReturn(rejected);

        ResponseEntity<Map<String, Object>> response =
                checkoutService.createIntent(requestBody("UPI", "key-1", "BREW3"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("expired"));
        verify(intentDao, never()).save(any(PaymentIntent.class));
    }

    @Test
    void createIntentCashSkipsProvider() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByUserIdAndClientKey(anyString(), anyString())).thenReturn(null);
        when(pricingService.price(any(), eq((String) null), eq("customer@x.io"))).thenReturn(pricing(200, 0, 0));
        when(intentDao.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response =
                checkoutService.createIntent(requestBody("CASH", "key-1", null));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("COUNTER", response.getBody().get("provider"));
        verify(sandboxProvider, never()).createOrder(anyLong(), anyString(), anyString());
    }

    @Test
    void createIntentRequiresAuthentication() {
        when(jwtFilter.getCurrentUser()).thenReturn(null);
        ResponseEntity<Map<String, Object>> response =
                checkoutService.createIntent(requestBody("UPI", "key-1", null));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // ---------------------------------------------------------------- confirm — verification + assisted rules

    @Test
    void confirmVerifiesProviderSignature() {
        activateRazorpay();
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByReference("PAY-1")).thenReturn(pendingIntent("PAY-1", "UPI"));
        when(razorpayProvider.verifyCheckoutSignature("order_1", "pay_1", "sig-good")).thenReturn(true);
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("status", "SUCCESS");
        when(settlementService.settleSuccess("PAY-1", "pay_1")).thenReturn(receipt);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        body.put("providerOrderId", "order_1");
        body.put("providerPaymentId", "pay_1");
        body.put("signature", "sig-good");

        ResponseEntity<Map<String, Object>> response = checkoutService.confirm(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(settlementService).settleSuccess("PAY-1", "pay_1");
    }

    @Test
    void confirmRejectsBadSignature() {
        activateRazorpay();
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByReference("PAY-1")).thenReturn(pendingIntent("PAY-1", "UPI"));
        when(razorpayProvider.verifyCheckoutSignature(anyString(), anyString(), anyString())).thenReturn(false);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        body.put("providerOrderId", "order_1");
        body.put("providerPaymentId", "pay_1");
        body.put("signature", "sig-bad");

        ResponseEntity<Map<String, Object>> response = checkoutService.confirm(body);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(settlementService, never()).settleSuccess(anyString(), anyString());
    }

    @Test
    void confirmAssistedCashIsAllowedForIntentOwnerWithoutAdmin() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByReference("PAY-CASH")).thenReturn(pendingIntent("PAY-CASH", "CASH"));
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("status", "SUCCESS");
        when(settlementService.settleSuccess("PAY-CASH", null)).thenReturn(receipt);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-CASH");
        body.put("assisted", "true");

        ResponseEntity<Map<String, Object>> response = checkoutService.confirm(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(settlementService).settleSuccess("PAY-CASH", null);
    }

    @Test
    void confirmAssistedUpiRequiresAdmin() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByReference("PAY-1")).thenReturn(pendingIntent("PAY-1", "UPI"));
        when(jwtFilter.isAdmin()).thenReturn(false);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        body.put("assisted", "true");

        ResponseEntity<Map<String, Object>> response = checkoutService.confirm(body);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(settlementService, never()).settleSuccess(anyString(), anyString());
    }

    @Test
    void confirmDuplicateReturnsSameReceiptWithoutResettling() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        PaymentIntent done = pendingIntent("PAY-1", "UPI");
        done.setStatus(PaymentIntent.STATUS_SUCCESS);
        done.setBillId(7);
        when(intentDao.findByReference("PAY-1")).thenReturn(done);
        Bill bill = new Bill();
        bill.setId(7);
        bill.setUuid("BILL-7");
        when(billService.getBillById(7)).thenReturn(bill);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        body.put("providerOrderId", "order_1");
        body.put("providerPaymentId", "pay_1");
        body.put("signature", "sig");

        ResponseEntity<Map<String, Object>> response = checkoutService.confirm(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("alreadyCompleted"));
        verify(settlementService, never()).settleSuccess(anyString(), anyString());
    }

    @Test
    void confirmFailedIntentIsConflict() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        PaymentIntent failed = pendingIntent("PAY-1", "UPI");
        failed.setStatus(PaymentIntent.STATUS_FAILED);
        when(intentDao.findByReference("PAY-1")).thenReturn(failed);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        ResponseEntity<Map<String, Object>> response = checkoutService.confirm(body);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void otherUsersIntentIsNotFound() {
        when(jwtFilter.getCurrentUser()).thenReturn("attacker@x.io");
        when(jwtFilter.isAdmin()).thenReturn(false);
        when(intentDao.findByReference("PAY-1")).thenReturn(pendingIntent("PAY-1", "UPI"));

        ResponseEntity<Map<String, Object>> response =
                checkoutService.intentStatus("PAY-1");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ---------------------------------------------------------------- webhook

    @Test
    void webhookRejectsInvalidSignature() {
        activateRazorpay();
        when(razorpayProvider.verifyWebhookSignature(anyString(), anyString())).thenReturn(false);
        ResponseEntity<String> response = checkoutService.webhook("{}", "bad");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void webhookSettlesCapturedPayment() {
        // Razorpay active mode for a real webhook test.
        activateRazorpay();

        when(razorpayProvider.verifyWebhookSignature(anyString(), eq("sig-ok"))).thenReturn(true);
        when(intentDao.findByProviderOrderId("order_1")).thenReturn(pendingIntent("PAY-1", "UPI"));
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("status", "SUCCESS");
        when(settlementService.settleSuccess("PAY-1", "pay_9")).thenReturn(receipt);

        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_9\",\"order_id\":\"order_1\",\"status\":\"captured\"}}}}";
        ResponseEntity<String> response = checkoutService.webhook(body, "sig-ok");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(settlementService).settleSuccess("PAY-1", "pay_9");
    }

    @Test
    void webhookMarksFailedPayment() {
        activateRazorpay();

        when(razorpayProvider.verifyWebhookSignature(anyString(), eq("sig-ok"))).thenReturn(true);
        when(intentDao.findByProviderOrderId("order_1")).thenReturn(pendingIntent("PAY-1", "UPI"));
        when(intentDao.transitionStatus(eq("PAY-1"), eq(PaymentIntent.STATUS_FAILED), anyCollection())).thenReturn(1);

        String body = "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_9\",\"order_id\":\"order_1\",\"status\":\"failed\"}}}}";
        ResponseEntity<String> response = checkoutService.webhook(body, "sig-ok");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(intentDao).transitionStatus(eq("PAY-1"), eq(PaymentIntent.STATUS_FAILED), anyCollection());
        verify(settlementService, never()).settleSuccess(anyString(), anyString());
    }

    @Test
    void webhookIgnoresUnknownOrders() {
        activateRazorpay();

        when(razorpayProvider.verifyWebhookSignature(anyString(), eq("sig-ok"))).thenReturn(true);
        when(intentDao.findByProviderOrderId("order_unknown")).thenReturn(null);

        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_9\",\"order_id\":\"order_unknown\"}}}}";
        ResponseEntity<String> response = checkoutService.webhook(body, "sig-ok");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(settlementService, never()).settleSuccess(anyString(), anyString());
    }

    // ---------------------------------------------------------------- sandbox gating

    @Test
    void sandboxSettleIsForbiddenWithRealProvider() {
        lenient().when(razorpayProvider.configured()).thenReturn(true);
        lenient().when(razorpayProvider.id()).thenReturn("RAZORPAY");
        ReflectionTestUtils.setField(checkoutService, "providerSetting", "RAZORPAY");
        checkoutService.resolveProvider();

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        body.put("outcome", "SUCCESS");
        ResponseEntity<Map<String, Object>> response = checkoutService.sandboxSettle(body);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void sandboxSettleSuccessGoesThroughSettlement() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        when(intentDao.findByReference("PAY-1")).thenReturn(pendingIntent("PAY-1", "UPI"));
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("status", "SUCCESS");
        when(settlementService.settleSuccess(eq("PAY-1"), anyString())).thenReturn(receipt);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        body.put("outcome", "SUCCESS");
        ResponseEntity<Map<String, Object>> response = checkoutService.sandboxSettle(body);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ---------------------------------------------------------------- settlement core (separate bean)

    @Test
    void settlementCreatesBillAndPaymentExactlyOnce() {
        PaymentIntent pending = pendingIntent("PAY-1", "UPI");
        PaymentIntent reloaded = pendingIntent("PAY-1", "UPI");
        reloaded.setStatus(PaymentIntent.STATUS_SUCCESS);
        when(intentDao.findByReference("PAY-1")).thenReturn(pending, reloaded);
        when(intentDao.transitionStatusWithPayment(eq("PAY-1"), eq("pay_1"),
                eq(PaymentIntent.STATUS_SUCCESS), anyCollection())).thenReturn(1);
        Bill bill = new Bill();
        bill.setId(11);
        bill.setUuid("BILL-11");
        when(billService.createBillFromIntent(reloaded)).thenReturn(bill);
        when(billService.getBillById(11)).thenReturn(bill);
        when(paymentDao.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(77L);
            return p;
        });

        Map<String, Object> receipt = settlement.settleSuccess("PAY-1", "pay_1");

        assertEquals("SUCCESS", receipt.get("status"));
        assertEquals("BILL-11", receipt.get("billUuid"));
        assertEquals(450, receipt.get("amount"));
        verify(billService).createBillFromIntent(any(PaymentIntent.class));
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentDao).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();
        assertEquals("PAY-1", saved.getTransactionId());
        assertEquals(450, (int) saved.getAmount()); // SERVER amount, never client input
        assertTrue(saved.isStatus());               // true ONLY after verification
    }

    @Test
    void settlementIsIdempotentWhenLosingTheRace() {
        PaymentIntent pending = pendingIntent("PAY-1", "UPI");
        PaymentIntent done = pendingIntent("PAY-1", "UPI");
        done.setStatus(PaymentIntent.STATUS_SUCCESS);
        done.setBillId(11);
        done.setPaymentId(77L);
        // first read -> PENDING; transition loses (0); reload -> SUCCESS (winner finished)
        when(intentDao.findByReference("PAY-1")).thenReturn(pending, done);
        when(intentDao.transitionStatusWithPayment(anyString(), anyString(), anyString(), anyCollection())).thenReturn(0);
        Bill bill = new Bill();
        bill.setId(11);
        bill.setUuid("BILL-11");
        when(billService.getBillById(11)).thenReturn(bill);

        Map<String, Object> receipt = settlement.settleSuccess("PAY-1", "pay_1");

        assertEquals(Boolean.TRUE, receipt.get("alreadyCompleted"));
        assertEquals("BILL-11", receipt.get("billUuid"));
        verify(billService, never()).createBillFromIntent(any(PaymentIntent.class)); // NO second bill
        verify(paymentDao, never()).save(any(Payment.class));                        // NO second payment
    }

    @Test
    void settlementReturnsEarlyWhenAlreadySuccessful() {
        PaymentIntent done = pendingIntent("PAY-1", "UPI");
        done.setStatus(PaymentIntent.STATUS_SUCCESS);
        done.setBillId(11);
        when(intentDao.findByReference("PAY-1")).thenReturn(done);
        Bill bill = new Bill();
        bill.setId(11);
        bill.setUuid("BILL-11");
        when(billService.getBillById(11)).thenReturn(bill);

        Map<String, Object> receipt = settlement.settleSuccess("PAY-1", "pay_dup");
        assertEquals(Boolean.TRUE, receipt.get("alreadyCompleted"));
        verify(billService, never()).createBillFromIntent(any());
        verify(paymentDao, never()).save(any());
    }

    @Test
    void settlementCashCreatesBillOnlyNoPaymentRecord() {
        PaymentIntent cash = pendingIntent("PAY-CASH", "CASH");
        PaymentIntent reloaded = pendingIntent("PAY-CASH", "CASH");
        reloaded.setStatus(PaymentIntent.STATUS_SUCCESS);
        when(intentDao.findByReference("PAY-CASH")).thenReturn(cash, reloaded);
        when(intentDao.transitionStatus(eq("PAY-CASH"), eq(PaymentIntent.STATUS_SUCCESS), anyCollection())).thenReturn(1);
        Bill bill = new Bill();
        bill.setId(12);
        bill.setUuid("BILL-12");
        when(billService.createBillFromIntent(reloaded)).thenReturn(bill);
        when(billService.getBillById(12)).thenReturn(bill);

        Map<String, Object> receipt = settlement.settleSuccess("PAY-CASH", null);

        assertEquals("BILL-12", receipt.get("billUuid"));
        verify(billService).createBillFromIntent(reloaded);
        verify(paymentDao, never()).save(any(Payment.class)); // cash is not claimed money
    }

    @Test
    void settlementPaymentRowIsUpiTypeWithUpiIdForUpiMethod() {
        PaymentIntent upi = pendingIntent("PAY-UPI", "UPI");
        upi.setUpiId("payer@bank");
        PaymentIntent reloaded = pendingIntent("PAY-UPI", "UPI");
        reloaded.setStatus(PaymentIntent.STATUS_SUCCESS);
        reloaded.setUpiId("payer@bank");
        when(intentDao.findByReference("PAY-UPI")).thenReturn(upi, reloaded);
        when(intentDao.transitionStatusWithPayment(anyString(), anyString(), anyString(), anyCollection())).thenReturn(1);
        Bill bill = new Bill();
        bill.setId(13);
        when(billService.createBillFromIntent(reloaded)).thenReturn(bill);
        when(paymentDao.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(78L);
            return p;
        });

        settlement.settleSuccess("PAY-UPI", "pay_2");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentDao).save(captor.capture());
        assertTrue(captor.getValue() instanceof UPIPayment);
        assertEquals("payer@bank", captor.getValue().getUpiId());
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void expiredIntentsTransitionToExpired() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        PaymentIntent stale = pendingIntent("PAY-1", "UPI");
        stale.setExpiresAt(new java.util.Date(System.currentTimeMillis() - 1000));
        when(intentDao.findByReference("PAY-1")).thenReturn(stale);
        when(intentDao.transitionStatus(eq("PAY-1"), eq(PaymentIntent.STATUS_EXPIRED), anyCollection())).thenReturn(1);

        ResponseEntity<Map<String, Object>> response = checkoutService.intentStatus("PAY-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(PaymentIntent.STATUS_EXPIRED, response.getBody().get("status"));
    }

    @Test
    void cancelSuccessfulIntentIsRejected() {
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x.io");
        PaymentIntent done = pendingIntent("PAY-1", "UPI");
        done.setStatus(PaymentIntent.STATUS_SUCCESS);
        when(intentDao.findByReference("PAY-1")).thenReturn(done);

        Map<String, Object> body = new HashMap<>();
        body.put("reference", "PAY-1");
        ResponseEntity<String> response = checkoutService.cancel(body);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }
}
