package com.inn.cafe.RestImpl;

import com.inn.cafe.Constents.CafeConstants;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.Rest.BillRest;
import com.inn.cafe.Service.BillService;
import com.inn.cafe.Utils.CafeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
public class BillRestImpl implements BillRest {

    @Autowired
    BillService billService;

    @Override
    public ResponseEntity<List<Bill>> getMyBills() {
        return billService.getMyBills();
    }

    @Override
    public ResponseEntity<String> generateReport(Map<String, Object> requestMap) {
        try{
            return billService.generateReport(requestMap);
        }catch (Exception ex){
        ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * We use `Object` as the value type in `Map<String, Object>` because the request body
     * can contain different data types (String, Integer, JSON objects, etc.).
     * <p>
     * For example, the request body may include:
     * {
     * "name": "John Doe",
     * "email": "john@example.com",
     * "total": 500,
     * "productDetails": [{"id": 1, "name": "Pizza", "price": 200}, {"id": 2, "name": "Pasta", "price": 300}]
     * }
     * <p>
     * Since `productDetails` is a JSON array (which can be a List or another Map), using `Object` allows
     * us to handle different data types flexibly.
     */
    @Override
    public ResponseEntity<List<Bill>> getBills() {
        try {
            return billService.getBills();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new ResponseEntity<>(new java.util.ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR); // FIX B.7: never return null
    }

    // method to download/get the pdf of bill in case of user has lost it and if needed in future for business purpose
    @Override
    public ResponseEntity<byte[]> getPdf(Map<String, Object> requestMap) {
        try {
            return billService.getPdf(requestMap);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new ResponseEntity<>(new byte[0], HttpStatus.INTERNAL_SERVER_ERROR); // FIX B.7: never return null
    }

    // FIX B.3: browser-usable POST variant (fetch cannot send GET bodies)
    @Override
    public ResponseEntity<byte[]> getPdfPost(Map<String, Object> requestMap) {
        return getPdf(requestMap);
    }

    @Override
    public ResponseEntity<String> deleteBill(Integer id) {
        try{
            return billService.deleteBill(id);
        }catch (Exception ex){
        ex.printStackTrace();
        }

        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
