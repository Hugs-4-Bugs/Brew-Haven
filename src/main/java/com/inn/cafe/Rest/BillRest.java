package com.inn.cafe.Rest;

import com.inn.cafe.POJO.Bill;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequestMapping(path = "/bill")
public interface BillRest {

    @PostMapping(path = "/generateReport")
    ResponseEntity<String> generateReport(@RequestBody Map<String, Object> requestMap);

    // API to get the Bills based on the particular Admin [for ex. admin X will get the list of bill which he has generated only followed by latest bill on top]
    @GetMapping(path = "/getBills")
    ResponseEntity<List<Bill>> getBills();

    // API to download the pdf of bill (legacy GET variant — kept for curl/legacy clients).
    // NOTE: GET with a request body cannot be sent from browsers (fetch spec forbids it),
    // which made this endpoint unusable from any web frontend (DEFECT_REGISTER B.3).
    @GetMapping(path = "/getPdf")
    ResponseEntity<byte[]> getPdf(@RequestBody Map<String, Object> requestMap);

    // FIX (DEFECT_REGISTER B.3): browser-usable POST variant with identical semantics.
    @PostMapping(path = "/getPdf")
    ResponseEntity<byte[]> getPdfPost(@RequestBody Map<String, Object> requestMap);

    // API to delete the bill
    @PostMapping(path = "/delete/{id}")
    ResponseEntity<String> deleteBill(@PathVariable Integer id);

    // ADDITIVE: the signed-in customer's own orders (own email only).
    @GetMapping(path = "/myBills")
    ResponseEntity<List<Bill>> getMyBills();
}
