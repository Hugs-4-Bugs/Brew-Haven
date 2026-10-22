package com.inn.cafe.Service;

import com.inn.cafe.POJO.Bill;
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
}
