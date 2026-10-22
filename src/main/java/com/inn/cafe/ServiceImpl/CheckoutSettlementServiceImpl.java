package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.PaymentDao;
import com.inn.cafe.DAO.PaymentIntentDao;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.CardPayment;
import com.inn.cafe.POJO.Payment;
import com.inn.cafe.POJO.PaymentIntent;
import com.inn.cafe.POJO.PaymentType;
import com.inn.cafe.POJO.UPIPayment;
import com.inn.cafe.Service.BillService;
import com.inn.cafe.Service.CheckoutSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Transactional settlement core — see Service/CheckoutSettlementService.
 *
 * Concurrency safety: the PENDING -> SUCCESS transition is a conditional
 * UPDATE (PaymentIntentDao.transitionStatus*) executed at the database level.
 * Exactly one racing transaction wins it; every loser observes the winner's
 * result and returns it idempotently. The unique constraint on
 * payment_intent.bill_id is the final backstop against double bills.
 */
@Slf4j
@Service
public class CheckoutSettlementServiceImpl implements CheckoutSettlementService {

    @Autowired
    private PaymentIntentDao intentDao;

    @Autowired
    private PaymentDao paymentDao;

    @Autowired
    private BillService billService;

    @Override
    @Transactional
    public Map<String, Object> settleSuccess(String reference, String providerPaymentId) {
        PaymentIntent intent = intentDao.findByReference(reference);
        if (intent == null) {
            throw new IllegalArgumentException("Payment reference not found.");
        }
        if (PaymentIntent.STATUS_SUCCESS.equals(intent.getStatus())) {
            // Idempotent: a duplicate confirm / duplicate webhook / retry after
            // timeout returns the SAME result — never a second bill or payment.
            return receiptBody(intent, true);
        }

        String pid = (providerPaymentId == null || providerPaymentId.isBlank()) ? null : providerPaymentId;
        java.util.Collection<String> openStates = Arrays.asList(
                PaymentIntent.STATUS_CREATED, PaymentIntent.STATUS_PENDING);
        int updated = (pid == null)
                ? intentDao.transitionStatus(reference, PaymentIntent.STATUS_SUCCESS, openStates)
                : intentDao.transitionStatusWithPayment(reference, pid, PaymentIntent.STATUS_SUCCESS, openStates);
        if (updated == 0) {
            PaymentIntent current = intentDao.findByReference(reference);
            if (current != null && PaymentIntent.STATUS_SUCCESS.equals(current.getStatus())) {
                return receiptBody(current, true); // lost the race — idempotent result
            }
            throw new IllegalStateException("Payment could not be confirmed (state="
                    + (current == null ? "UNKNOWN" : current.getStatus()) + ").");
        }
        intent = intentDao.findByReference(reference);
        // The conditional UPDATE above bypasses the persistence-context entity —
        // sync the in-memory state so the returned receipt can never echo a stale
        // status (DB row is already correct; this is a response-body fix only).
        intent.setStatus(PaymentIntent.STATUS_SUCCESS);
        if (pid != null) {
            intent.setProviderPaymentId(pid);
        }

        // ---- bill: created EXACTLY once (guarded here + unique bill_id backstop)
        if (intent.getBillId() == null) {
            Bill bill = billService.createBillFromIntent(intent);
            if (bill == null || bill.getId() == null) {
                throw new IllegalStateException("Bill creation failed for " + reference);
            }
            intent.setBillId(bill.getId());
        }

        // ---- payment record: money-claiming methods only, EXACTLY once.
        // Counter-CASH creates the bill only — cash in a drawer is not
        // provider-verified money, so no payments row claims it.
        if (intent.getPaymentId() == null && !PaymentIntent.METHOD_CASH.equals(intent.getMethod())) {
            Payment payment = buildPayment(intent);
            intent.setPaymentId(paymentDao.save(payment).getId());
        }

        intentDao.save(intent);
        log.info("Intent {} settled SUCCESS — bill {} total {} (providerPaymentId {})",
                reference, intent.getBillId(), intent.getTotal(), pid);
        return receiptBody(intent, false);
    }

    private Payment buildPayment(PaymentIntent intent) {
        boolean upi = PaymentIntent.METHOD_UPI.equals(intent.getMethod());
        Payment payment = upi ? new UPIPayment() : new CardPayment();
        payment.setUserId(intent.getUserId());
        payment.setPaymentType(upi ? PaymentType.UPI : PaymentType.CARD);
        payment.setProvider(intent.getProvider());
        // The intent reference is the transaction id — one intent == one payment.
        payment.setTransactionId(intent.getReference());
        payment.setAmount(intent.getTotal()); // SERVER-computed amount, never client input
        payment.setStatus(true);              // set ONLY here, after verification
        payment.setTimestamp(LocalDateTime.now());
        if (upi && intent.getUpiId() != null) {
            payment.setUpiId(intent.getUpiId());
        }
        // Card data: NONE — the provider's hosted checkout tokenizes the card;
        // raw card numbers/CVV never touch this backend.
        return payment;
    }

    private Map<String, Object> receiptBody(PaymentIntent intent, boolean alreadyCompleted) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", intent.getReference());
        body.put("status", intent.getStatus());
        body.put("alreadyCompleted", alreadyCompleted);
        body.put("billUuid", null);
        body.put("transactionId", intent.getReference());
        body.put("amount", intent.getTotal());
        body.put("amountPaise", intent.getTotal() == null ? null : intent.getTotal() * 100L);
        body.put("method", intent.getMethod());
        body.put("provider", intent.getProvider());
        body.put("couponCode", intent.getCouponCode());
        body.put("subtotal", intent.getSubtotal());
        body.put("discount", intent.getDiscount());
        body.put("tax", intent.getTax());
        if (intent.getBillId() != null) {
            Bill bill = billService.getBillById(intent.getBillId());
            body.put("billUuid", bill == null ? null : bill.getUuid());
            body.put("billId", bill == null ? null : bill.getId());
        }
        return body;
    }
}
