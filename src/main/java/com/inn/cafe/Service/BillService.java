package com.inn.cafe.Service;

import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.PaymentIntent;
import org.springframework.data.repository.query.Param;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

public interface BillService {

    ResponseEntity<String> generateReport(Map<String, Object> requestMap);


    ResponseEntity<List<Bill>> getBills();

    // ADDITIVE: the signed-in customer's own orders (email == current user).
    ResponseEntity<List<Bill>> getMyBills();

    ResponseEntity<byte[]> getPdf(Map<String, Object> requestMap);


    ResponseEntity<String> deleteBill(@Param("id") Integer id);

    /** ADDITIVE: plain bill lookup used by the checkout receipt/status builders. */
    Bill getBillById(Integer id);

    /**
     * ADDITIVE: creates the bill for a successfully-settled payment intent.
     * The total and product details come EXCLUSIVELY from the server-computed
     * intent snapshot — never from client input. Reuses the existing PDF +
     * insert + campaign-attribution path; idempotent per intent (returns the
     * existing bill when intent.billId is already set).
     */
    Bill createBillFromIntent(PaymentIntent intent);
}
