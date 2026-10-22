package com.inn.cafe.Rest;

import com.inn.cafe.POJO.Product;
import com.inn.cafe.Wrapper.ProductWrapper;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;


@RequestMapping(path = "/product")
public interface ProductRest {

    @PostMapping(path = "/add")
    ResponseEntity<String> addNewProduct(@RequestBody Map<String, String> requestMap);

    // API to get all the product present inside the particular category
    @GetMapping(path = "/get")
    ResponseEntity<List<ProductWrapper>> getAllProduct();

    // API to update the product
    @PostMapping(path = "/update")
    ResponseEntity<String> updateProduct(@RequestBody Map<String, String> requestMap);

    // API for delete the product by id
    @PostMapping(path = "/delete/{id}")
    ResponseEntity<String> deleteProduct(@PathVariable Integer id);

    // API to update the product Status
    @PostMapping(path = "/updateStatus")
    ResponseEntity<String> updateStatus(@RequestBody Map<String, String> requestMap);

    // ADDITIVE (admin-only): set/clear the featured (signature) merchandising flag.
    @PostMapping(path = "/setFeatured")
    ResponseEntity<String> setFeatured(@RequestBody Map<String, String> requestMap);

    // API to get products by category
    @GetMapping(path = "/getByCategory/{id}")
    ResponseEntity<List<ProductWrapper>> getByCategory(@PathVariable Integer id);

    // API to get products by id
    @GetMapping(path = "/getById/{id}")
    ResponseEntity<ProductWrapper> getProductById(@PathVariable Integer id);

    // API to get the product from the Excel file
    @PostMapping(path = "/uploadExcel")
    ResponseEntity<String> uploadExcel(@RequestParam("file") MultipartFile file);

    // API to implement pagination
    @GetMapping(path = "/productList")
    Page<Product> getPaginatedProducts(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "5") int size);

}