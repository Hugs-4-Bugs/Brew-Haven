package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.PaymentIntentDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.PaymentIntent;
import com.inn.cafe.Service.BillService;
import com.inn.cafe.Service.CheckoutService;
import com.inn.cafe.Service.CheckoutSettlementService;
import com.inn.cafe.Service.PaymentService;
import com.inn.cafe.Service.PricingService;
import com.inn.cafe.Utils.payments.PaymentProvider;
import com.inn.cafe.Utils.payments.RazorpayProvider;
import com.inn.cafe.Utils.payments.SandboxProvider;
import com.inn.cafe.Wrapper.PricingResult;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Checkout orchestration — the provider-ready payment flow:
 *
 *   cart -> server pricing -> coupon validation -> final payable amount
 *       -> payment intent (idempotent) -> provider order for the EXACT amount
 *       -> UPI / Card / Cash-at-counter
 *       -> verification (provider signature / webhook / operator attestation)
 *       -> bill + payment + revenue record (exactly once)
 *
 * The payable amount is computed EXCLUSIVELY by PricingService from live DB
 * prices; the API accepts only {productId, quantity} pairs — there is no code
 * path by which a browser-supplied amount can reach the provider or the bill.
 */
@Slf4j
@Service
public class CheckoutServiceImpl implements CheckoutService {

    @Autowired
    private PaymentIntentDao intentDao;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private BillService billService;

    @Autowired
    private CheckoutSettlementService settlementService;

    @Autowired
    private JwtFilter jwtFilter;

    @Autowired
    private RazorpayProvider razorpayProvider;

    @Autowired
    private SandboxProvider sandboxProvider;

    /** AUTO | SANDBOX | RAZORPAY (env PAYMENT_PROVIDER). */
    @Value("${cafe.payment.provider:AUTO}")
    private String providerSetting;

    @Value("${cafe.payment.intent-expiry-minutes:15}")
    private int expiryMinutes;

    private PaymentProvider active;

    @PostConstruct
    public void resolveProvider() {
        String setting = providerSetting == null ? "" : providerSetting.trim().toUpperCase(Locale.ROOT);
        if ("RAZORPAY".equals(setting)) {
            if (!razorpayProvider.configured()) {
                // Production requires real secrets — fail fast instead of silently faking.
                throw new IllegalStateException(
                        "PAYMENT_PROVIDER=RAZORPAY requires PAYMENT_PROVIDER_KEY and PAYMENT_PROVIDER_SECRET.");
            }
            active = razorpayProvider;
        } else if ("SANDBOX".equals(setting)) {
            active = sandboxProvider;
            if (razorpayProvider.configured()) {
                log.warn("Payment provider forced to SANDBOX although Razorpay keys are present — real payments are DISABLED.");
            }
        } else {
            active = razorpayProvider.configured() ? razorpayProvider : sandboxProvider;
            if (!razorpayProvider.configured()) {
                log.warn("No payment provider keys configured — payments run in SANDBOX mode. "
                        + "Set PAYMENT_PROVIDER_KEY / PAYMENT_PROVIDER_SECRET for real payments.");
            }
        }
        log.info("Active payment provider: {}", active.id());
    }

    // ================================================================= price

    @Override
    public ResponseEntity<Map<String, Object>> price(Map<String, Object> requestMap) {
        try {
            List<Map<String, Object>> items = readItems(requestMap);
            String coupon = readString(requestMap.get("couponCode"));
            String email = firstNonBlank(readString(requestMap.get("email")), jwtFilter.getCurrentUser());
            PricingResult result = pricingService.price(items, coupon, email);
            return ResponseEntity.ok(pricingBody(result));
        } catch (IllegalArgumentException e) {
            return badRequest(safe(e.getMessage()));
        } catch (Exception e) {
            log.error("price failed: {}", e.getMessage());
            return internalError();
        }
    }

    // ================================================================= createIntent

    @Override
    public ResponseEntity<Map<String, Object>> createIntent(Map<String, Object> requestMap) {
        try {
            String user = jwtFilter.getCurrentUser();
            if (isBlank(user)) {
                return unauthorized("Sign in required.");
            }
            List<Map<String, Object>> items = readItems(requestMap);
            String clientKey = readString(requestMap.get("clientKey"));
            if (isBlank(clientKey) || clientKey.length() > 140) {
                return badRequest("clientKey is required (max 140 chars).");
            }
            String method = uppercase(readString(requestMap.get("method")));
            if (!PaymentIntent.METHOD_UPI.equals(method)
                    && !PaymentIntent.METHOD_CARD.equals(method)
                    && !PaymentIntent.METHOD_CASH.equals(method)) {
                return badRequest("method must be UPI, CARD or CASH.");
            }
            String name = readString(requestMap.get("name"));
            String contact = readString(requestMap.get("contactNumber"));
            String email = firstNonBlank(readString(requestMap.get("email")), user);
            if (isBlank(name) || isBlank(contact) || isBlank(email)) {
                return badRequest("name, contactNumber and email are required.");
            }
            String coupon = readString(requestMap.get("couponCode"));
            String upiId = readString(requestMap.get("upiId"));

            // ---- IDEMPOTENCY: (userId, clientKey) always maps to the same intent.
            // Double clicks, network retries, browser refreshes and API retries
            // with the same key return the existing intent — never a duplicate.
            PaymentIntent existing = intentDao.findByUserIdAndClientKey(user, clientKey);
            if (existing != null) {
                expireIfNeeded(existing);
                if (PaymentIntent.STATUS_SUCCESS.equals(existing.getStatus())) {
                    return ResponseEntity.ok(settlementReceipt(existing, true));
                }
                if (PaymentIntent.STATUS_CREATED.equals(existing.getStatus())
                        || PaymentIntent.STATUS_PENDING.equals(existing.getStatus())) {
                    return ResponseEntity.ok(intentBody(existing));
                }
                return conflict("This payment attempt already "
                        + existing.getStatus().toLowerCase(Locale.ROOT) + ". Start a new payment.");
            }

            // ---- AUTHORITATIVE PRICING (amounts are never accepted from the client)
            PricingResult pricing = pricingService.price(items, coupon, email);
            if (!isBlank(coupon) && !pricing.isCouponApplied()) {
                return badRequest("Coupon not applied: "
                        + (pricing.getCouponRejectionReason() == null ? "invalid coupon" : pricing.getCouponRejectionReason()));
            }
            String itemsJson = itemsJson(pricing);

            PaymentIntent intent = new PaymentIntent();
            intent.setReference(newReference());
            intent.setClientKey(clientKey);
            intent.setUserId(user);
            intent.setStatus(PaymentIntent.STATUS_CREATED);
            intent.setMethod(method);
            // ONE order carries at most ONE coupon (1:1 rule) — enforced by the
            // single coupon_code column here and re-validated at settlement.
            intent.setCouponCode(pricing.isCouponApplied() ? pricing.getCouponCode() : null);
            intent.setSubtotal(pricing.getSubtotal());
            intent.setDiscount(pricing.getDiscount());
            intent.setTax(pricing.getTax());
            intent.setTotal(pricing.getTotal());
            intent.setItemsJson(itemsJson);
            intent.setCustomerName(name);
            intent.setContactNumber(contact);
            intent.setEmail(email);
            intent.setUpiId(isBlank(upiId) ? null : upiId.trim());
            intent.setCreatedAt(new Date());
            intent.setUpdatedAt(new Date());
            intent.setExpiresAt(new Date(System.currentTimeMillis() + expiryMinutes * 60_000L));

            if (!PaymentIntent.METHOD_CASH.equals(method)) {
                // Provider order for the EXACT server-computed amount (paise).
                com.inn.cafe.Utils.payments.ProviderOrder order =
                        active.createOrder(pricing.getTotalPaise(), pricing.getCurrency(), intent.getReference());
                intent.setProvider(active.id());
                intent.setProviderOrderId(order.getProviderOrderId());
                intent.setStatus(PaymentIntent.STATUS_PENDING);
            } else {
                intent.setProvider("COUNTER");
            }

            try {
                intent = intentDao.save(intent);
            } catch (DataIntegrityViolationException race) {
                PaymentIntent winner = intentDao.findByUserIdAndClientKey(user, clientKey);
                if (winner != null) {
                    return ResponseEntity.ok(intentBody(winner));
                }
                throw race;
            }
            log.info("Payment intent {} created — {} {} total Rs.{} (subtotal {} discount {} tax {} coupon {})",
                    intent.getReference(), user, method, intent.getTotal(),
                    intent.getSubtotal(), intent.getDiscount(), intent.getTax(), intent.getCouponCode());
            return ResponseEntity.ok(intentBody(intent));
        } catch (IllegalArgumentException e) {
            return badRequest(safe(e.getMessage()));
        } catch (IllegalStateException e) {
            log.error("createIntent provider failure: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(messageBody(safe(e.getMessage())));
        } catch (Exception e) {
            log.error("createIntent failed", e);
            return internalError();
        }
    }

    // ================================================================= status / qr

    @Override
    public ResponseEntity<Map<String, Object>> intentStatus(String reference) {
        try {
            PaymentIntent intent = loadOwned(reference);
            if (intent == null) {
                return notFoundMap();
            }
            expireIfNeeded(intent);
            return ResponseEntity.ok(statusBody(intent));
        } catch (Exception e) {
            log.error("intentStatus failed: {}", e.getMessage());
            return internalError();
        }
    }

    @Override
    public ResponseEntity<String> intentQr(String reference) {
        try {
            PaymentIntent intent = loadOwned(reference);
            if (intent == null) {
                return jsonError(HttpStatus.NOT_FOUND, "Payment reference not found.");
            }
            expireIfNeeded(intent);
            if (!PaymentIntent.METHOD_UPI.equals(intent.getMethod())) {
                return jsonError(HttpStatus.CONFLICT, "QR is only available for UPI payments.");
            }
            if (!PaymentIntent.STATUS_PENDING.equals(intent.getStatus())) {
                return jsonError(HttpStatus.CONFLICT, "This payment is " + intent.getStatus() + ".");
            }
            boolean sandbox = active != null && SandboxProvider.ID.equals(active.id());
            // In real-provider mode the QR is for counter-assisted use by staff only —
            // customers pay through the provider's verified checkout window instead.
            if (!sandbox && !jwtFilter.isAdmin()) {
                return jsonError(HttpStatus.CONFLICT, "Use the payment window to complete this payment.");
            }
            String qr = paymentService.generateQrCode(intent.getTotal(), "Order " + intent.getReference());
            return ResponseEntity.ok(qr);
        } catch (IllegalStateException e) {
            return jsonError(HttpStatus.SERVICE_UNAVAILABLE, safe(e.getMessage()));
        } catch (Exception e) {
            log.error("intentQr failed: {}", e.getMessage());
            return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "Something Went Wrong");
        }
    }

    // ================================================================= confirm

    @Override
    public ResponseEntity<Map<String, Object>> confirm(Map<String, Object> requestMap) {
        try {
            String reference = readString(requestMap.get("reference"));
            if (isBlank(reference)) {
                return badRequest("reference is required.");
            }
            PaymentIntent intent = loadOwned(reference);
            if (intent == null) {
                return notFoundMap();
            }
            expireIfNeeded(intent);

            if (PaymentIntent.STATUS_SUCCESS.equals(intent.getStatus())) {
                // Duplicate submission / retry — same receipt, no second bill/payment.
                return ResponseEntity.ok(settlementReceipt(intent, true));
            }
            if (!PaymentIntent.STATUS_PENDING.equals(intent.getStatus())
                    && !PaymentIntent.STATUS_CREATED.equals(intent.getStatus())) {
                return conflict("Payment is " + intent.getStatus().toLowerCase(Locale.ROOT)
                        + " and can no longer be confirmed.");
            }

            boolean assisted = Boolean.parseBoolean(String.valueOf(requestMap.get("assisted")));
            if (assisted) {
                // Counter-assisted attestation. CASH: the intent owner confirms
                // "I will pay at the counter" — a bill is created but NO payment
                // record (cash is not provider-verified money). UPI/Card assisted:
                // an ADMIN operator attests the customer paid at the counter.
                if (!PaymentIntent.METHOD_CASH.equals(intent.getMethod()) && !jwtFilter.isAdmin()) {
                    return forbidden("Only staff can confirm an assisted UPI/Card payment.");
                }
                Map<String, Object> receipt = settlementService.settleSuccess(reference,
                        PaymentIntent.METHOD_CASH.equals(intent.getMethod())
                                ? null : "ASSISTED-" + reference);
                return ResponseEntity.ok(receipt);
            }

            // Provider-verified confirmation (Razorpay checkout callback).
            String providerOrderId = readString(requestMap.get("providerOrderId"));
            String providerPaymentId = readString(requestMap.get("providerPaymentId"));
            String signature = readString(requestMap.get("signature"));
            if (isBlank(providerOrderId) || isBlank(providerPaymentId) || isBlank(signature)) {
                return badRequest("providerOrderId, providerPaymentId and signature are required.");
            }
            if (!providerOrderId.equals(intent.getProviderOrderId())) {
                return badRequest("Provider order does not match this payment reference.");
            }
            if (!active.verifyCheckoutSignature(providerOrderId, providerPaymentId, signature)) {
                log.warn("Checkout signature verification FAILED for intent {}", reference);
                return badRequest("Payment signature verification failed.");
            }
            return ResponseEntity.ok(settlementService.settleSuccess(reference, providerPaymentId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(safe(e.getMessage()));
        } catch (Exception e) {
            log.error("confirm failed", e);
            return internalError();
        }
    }

    // ================================================================= webhook

    @Override
    public ResponseEntity<String> webhook(String rawBody, String signatureHeader) {
        try {
            if (active == null || !active.verifyWebhookSignature(rawBody, signatureHeader)) {
                log.warn("Webhook rejected: invalid or missing signature.");
                return jsonError(HttpStatus.BAD_REQUEST, "invalid signature");
            }
            JSONObject event = new JSONObject(rawBody);
            String type = event.optString("event", "");
            JSONObject payload = event.optJSONObject("payload");
            JSONObject entity = payload == null ? null
                    : (payload.optJSONObject("payment") != null ? payload.optJSONObject("payment")
                    : payload.optJSONObject("order"));
            JSONObject paymentEntity = entity == null ? null : entity.optJSONObject("entity");

            if ("payment.captured".equals(type) && paymentEntity != null) {
                String paymentId = paymentEntity.optString("id");
                String orderId = paymentEntity.optString("order_id");
                PaymentIntent intent = isBlank(orderId) ? null : intentDao.findByProviderOrderId(orderId);
                if (intent == null) {
                    log.info("Webhook for unknown provider order {} ignored.", orderId);
                    return ResponseEntity.ok("{\"message\":\"ignored\"}");
                }
                Map<String, Object> receipt = settlementService.settleSuccess(intent.getReference(), paymentId);
                log.info("Webhook settled intent {} -> {}", intent.getReference(), receipt.get("status"));
            } else if ("payment.failed".equals(type) && paymentEntity != null) {
                String orderId = paymentEntity.optString("order_id");
                PaymentIntent intent = isBlank(orderId) ? null : intentDao.findByProviderOrderId(orderId);
                if (intent != null) {
                    int updated = intentDao.transitionStatus(intent.getReference(),
                            PaymentIntent.STATUS_FAILED,
                            Arrays.asList(PaymentIntent.STATUS_CREATED, PaymentIntent.STATUS_PENDING));
                    log.info("Webhook payment.failed for intent {} -> transitioned={}", intent.getReference(), updated);
                }
            }
            // Any other validly-signed event is acknowledged so the provider stops retrying.
            return ResponseEntity.ok("{\"message\":\"ok\"}");
        } catch (Exception e) {
            log.error("webhook processing failed", e);
            return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "Something Went Wrong");
        }
    }

    // ================================================================= sandbox settle

    @Override
    public ResponseEntity<Map<String, Object>> sandboxSettle(Map<String, Object> requestMap) {
        try {
            if (active == null || !SandboxProvider.ID.equals(active.id())) {
                // Hard gate: TEST settlement is impossible while a real provider is active.
                return forbidden("Sandbox settlement is disabled while a real payment provider is active.");
            }
            String reference = readString(requestMap.get("reference"));
            String outcome = uppercase(readString(requestMap.get("outcome")));
            if (isBlank(reference) || (!"SUCCESS".equals(outcome) && !"FAILED".equals(outcome))) {
                return badRequest("reference and outcome (SUCCESS|FAILED) are required.");
            }
            PaymentIntent intent = loadOwned(reference);
            if (intent == null) {
                return notFoundMap();
            }
            expireIfNeeded(intent);
            if (PaymentIntent.STATUS_SUCCESS.equals(intent.getStatus())) {
                return ResponseEntity.ok(settlementReceipt(intent, true));
            }
            if (!PaymentIntent.STATUS_PENDING.equals(intent.getStatus())
                    && !PaymentIntent.STATUS_CREATED.equals(intent.getStatus())) {
                return conflict("Payment is already " + intent.getStatus().toLowerCase(Locale.ROOT) + ".");
            }
            if ("FAILED".equals(outcome)) {
                intentDao.transitionStatus(reference, PaymentIntent.STATUS_FAILED,
                        Arrays.asList(PaymentIntent.STATUS_CREATED, PaymentIntent.STATUS_PENDING));
                Map<String, Object> body = statusBody(intentDao.findByReference(reference));
                return ResponseEntity.ok(body);
            }
            Map<String, Object> receipt = settlementService.settleSuccess(reference, "sbx_test_" + reference);
            return ResponseEntity.ok(receipt);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(safe(e.getMessage()));
        } catch (Exception e) {
            log.error("sandboxSettle failed", e);
            return internalError();
        }
    }

    // ================================================================= cancel

    @Override
    public ResponseEntity<String> cancel(Map<String, Object> requestMap) {
        try {
            String reference = readString(requestMap.get("reference"));
            if (isBlank(reference)) {
                return jsonError(HttpStatus.BAD_REQUEST, "reference is required.");
            }
            PaymentIntent intent = loadOwned(reference);
            if (intent == null) {
                return jsonError(HttpStatus.NOT_FOUND, "Payment reference not found.");
            }
            if (PaymentIntent.STATUS_SUCCESS.equals(intent.getStatus())) {
                return jsonError(HttpStatus.CONFLICT, "This payment already succeeded and cannot be cancelled.");
            }
            intentDao.transitionStatus(reference, PaymentIntent.STATUS_CANCELLED,
                    Arrays.asList(PaymentIntent.STATUS_CREATED, PaymentIntent.STATUS_PENDING));
            return ResponseEntity.ok("{\"message\":\"cancelled\"}");
        } catch (Exception e) {
            log.error("cancel failed: {}", e.getMessage());
            return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "Something Went Wrong");
        }
    }

    // ================================================================= helpers

    /** Loads an intent enforcing ownership (owner or admin); returns null when absent/not owned. */
    private PaymentIntent loadOwned(String reference) {
        if (isBlank(reference)) {
            return null;
        }
        PaymentIntent intent = intentDao.findByReference(reference.trim());
        if (intent == null) {
            return null;
        }
        String user = jwtFilter.getCurrentUser();
        if (user == null || (!user.equalsIgnoreCase(intent.getUserId()) && !jwtFilter.isAdmin())) {
            return null; // do not leak existence
        }
        return intent;
    }

    /** Lazy expiry — intents past their TTL transition to EXPIRED (no bill, no payment). */
    private void expireIfNeeded(PaymentIntent intent) {
        if (intent == null || intent.getExpiresAt() == null) {
            return;
        }
        String status = intent.getStatus();
        if ((PaymentIntent.STATUS_CREATED.equals(status) || PaymentIntent.STATUS_PENDING.equals(status))
                && intent.getExpiresAt().before(new Date())) {
            int updated = intentDao.transitionStatus(intent.getReference(), PaymentIntent.STATUS_EXPIRED,
                    Arrays.asList(PaymentIntent.STATUS_CREATED, PaymentIntent.STATUS_PENDING));
            if (updated > 0) {
                intent.setStatus(PaymentIntent.STATUS_EXPIRED);
            }
        }
    }

    private static String newReference() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readItems(Map<String, Object> requestMap) {
        Object raw = requestMap == null ? null : requestMap.get("items");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new IllegalArgumentException("Cart items are required.");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("Invalid cart item.");
            }
            items.add((Map<String, Object>) o);
        }
        return items;
    }

    private static String itemsJson(PricingResult pricing) throws org.json.JSONException {
        JSONArray array = new JSONArray();
        for (PricingResult.ItemLine line : pricing.getItems()) {
            JSONObject obj = new JSONObject();
            obj.put("id", line.getProductId());
            obj.put("name", line.getName());
            obj.put("category", line.getCategory());
            obj.put("quantity", line.getQuantity());
            obj.put("price", line.getUnitPrice());
            obj.put("total", line.getLineTotal());
            array.put(obj);
        }
        return array.toString();
    }

    // ---------------------------------------------------------------- response bodies

    private Map<String, Object> pricingBody(PricingResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subtotal", result.getSubtotal());
        body.put("discount", result.getDiscount());
        body.put("tax", result.getTax());
        body.put("total", result.getTotal());
        body.put("totalPaise", result.getTotalPaise());
        body.put("currency", result.getCurrency());
        body.put("couponApplied", result.isCouponApplied());
        body.put("couponCode", result.getCouponCode());
        body.put("couponName", result.getCouponName());
        body.put("couponOfferType", result.getCouponOfferType());
        body.put("couponOfferValue", result.getCouponOfferValue());
        body.put("couponRejectionReason", result.getCouponRejectionReason());
        List<Map<String, Object>> items = new ArrayList<>();
        if (result.getItems() != null) {
            for (PricingResult.ItemLine line : result.getItems()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", line.getProductId());
                m.put("name", line.getName());
                m.put("category", line.getCategory());
                m.put("unitPrice", line.getUnitPrice());
                m.put("quantity", line.getQuantity());
                m.put("lineTotal", line.getLineTotal());
                items.add(m);
            }
        }
        body.put("items", items);
        return body;
    }

    private Map<String, Object> intentBody(PaymentIntent intent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", intent.getReference());
        body.put("status", intent.getStatus());
        body.put("method", intent.getMethod());
        body.put("provider", intent.getProvider());
        body.put("providerOrderId", intent.getProviderOrderId());
        body.put("subtotal", intent.getSubtotal());
        body.put("discount", intent.getDiscount());
        body.put("tax", intent.getTax());
        body.put("total", intent.getTotal());
        body.put("amountPaise", intent.getTotal() == null ? null : intent.getTotal() * 100L);
        body.put("currency", "INR");
        body.put("couponCode", intent.getCouponCode());
        body.put("expiresAt", intent.getExpiresAt() == null ? null : intent.getExpiresAt().getTime());

        Map<String, Object> checkout = new LinkedHashMap<>();
        boolean sandbox = intent.getProvider() != null && SandboxProvider.ID.equals(intent.getProvider());
        checkout.put("mode", sandbox ? "SANDBOX" : "RAZORPAY");
        checkout.put("sandbox", sandbox);
        checkout.put("amountPaise", intent.getTotal() == null ? null : intent.getTotal() * 100L);
        checkout.put("currency", "INR");
        if (!sandbox) {
            // PUBLIC key id only — the key SECRET never leaves the server.
            checkout.put("keyId", razorpayProvider.keyId());
            checkout.put("providerOrderId", intent.getProviderOrderId());
        }
        body.put("checkout", checkout);
        return body;
    }

    private Map<String, Object> statusBody(PaymentIntent intent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", intent.getReference());
        body.put("status", intent.getStatus());
        body.put("method", intent.getMethod());
        body.put("total", intent.getTotal());
        body.put("amountPaise", intent.getTotal() == null ? null : intent.getTotal() * 100L);
        body.put("providerPaymentId", intent.getProviderPaymentId());
        if (intent.getBillId() != null) {
            com.inn.cafe.POJO.Bill bill = billService.getBillById(intent.getBillId());
            body.put("billUuid", bill == null ? null : bill.getUuid());
        } else {
            body.put("billUuid", null);
        }
        return body;
    }

    /** Receipt from an ALREADY-settled intent (idempotent duplicate responses). */
    private Map<String, Object> settlementReceipt(PaymentIntent intent, boolean alreadyCompleted) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", intent.getReference());
        body.put("status", intent.getStatus());
        body.put("alreadyCompleted", alreadyCompleted);
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
            com.inn.cafe.POJO.Bill bill = billService.getBillById(intent.getBillId());
            body.put("billUuid", bill == null ? null : bill.getUuid());
        } else {
            body.put("billUuid", null);
        }
        return body;
    }

    private static Map<String, Object> messageBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return body;
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(messageBody(message));
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(messageBody(message));
    }

    private ResponseEntity<Map<String, Object>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(messageBody(message));
    }

    private ResponseEntity<Map<String, Object>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(messageBody(message));
    }

    private ResponseEntity<Map<String, Object>> notFoundMap() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(messageBody("Payment reference not found."));
    }

    private ResponseEntity<Map<String, Object>> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(messageBody("Something Went Wrong"));
    }

    private static ResponseEntity<String> jsonError(HttpStatus status, String message) {
        return ResponseEntity.status(status).body("{\"message\":\"" + safe(message) + "\"}");
    }

    private static String readString(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return isBlank(a) ? b : a;
    }

    private static String uppercase(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "Something Went Wrong" : s.replaceAll("[\\r\\n]", " ");
    }
}
