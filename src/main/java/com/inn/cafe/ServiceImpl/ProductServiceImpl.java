package com.inn.cafe.ServiceImpl;

import com.inn.cafe.Constents.CafeConstants;
import com.inn.cafe.DAO.CategoryDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Category;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.Service.ProductService;
import com.inn.cafe.Utils.CafeUtils;
import com.inn.cafe.Wrapper.ProductWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.*;


@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductDao productDao;

    @Autowired
    private JwtFilter jwtFilter;

    @Autowired
    private CategoryDao categoryDao;


    @Override
    public ResponseEntity<String> addNewProduct(Map<String, String> requestMap) {
        log.info("Inside addNewProduct{}", requestMap);
        try {
            if (jwtFilter != null && jwtFilter.isAdmin()) {
                if (validateProductMap(requestMap, false)) {
                    // categoryId must resolve to an existing category (fix for DEFECT_REGISTER P.1:
                    // previously a missing/unknown categoryId caused NumberFormatException/NPE -> 500)
                    Integer categoryId = parsePositiveInt(requestMap.get("categoryId"));
                    Optional<Category> managedCategory = categoryId == null
                            ? Optional.empty() : categoryDao.findById(categoryId);
                    if (managedCategory.isEmpty()) {
                        return CafeUtils.getResponseEntity("Invalid Data: categoryId must be an id of an existing category", HttpStatus.BAD_REQUEST);
                    }
                    // FIX (discovered in E2E): getProductFromMap built a 'new Category(id)' with
                    // version=null; with @Version on Category Hibernate treats that instance as
                    // TRANSIENT and add/update fails with TransientPropertyValueException — the
                    // REST add/update endpoints were broken. Use the MANAGED entity instead.
                    Product product = getProductFromMap(requestMap, false, managedCategory.get());
                    // default status so newly added products are visible to 'status=true' queries
                    if (product.getStatus() == null || product.getStatus().isBlank()) {
                        product.setStatus("true");
                    }
                    productDao.save(product);
                    return CafeUtils.getResponseEntity("Product Added Successfully.", HttpStatus.OK);
                }
                return CafeUtils.getResponseEntity(CafeConstants.INVALID_DATA, HttpStatus.BAD_REQUEST);
            } else {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception ex) {
            log.error("addNewProduct failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }


    /**
     * The `validateProductMap` method checks if the request has a `name` and, if needed, an `id` to make sure the data is correct before adding or updating a product* .
     */

    /**
     * // THIS METHOD WILL ONLY UPDATE THE COMPLETE PRODUCT BUT NOT ONLY PRODUCT STATUS
     * <p>
     * //    private boolean validateProductMap(Map<String, String> requestMap, boolean validateId) {
     * ////        if (requestMap.containsKey("name") &&
     * ////                requestMap.containsKey("description") &&
     * ////                requestMap.containsKey("price") &&
     * ////                requestMap.containsKey("status")) {
     * //
     * //       // Or we can also write like this in 1 line
     * ////        if (requestMap.containsKey("name") && requestMap.containsKey("description") && requestMap.containsKey("price") && requestMap.containsKey("status")) {
     * //        if (requestMap.containsKey("name") && requestMap.containsKey("description") && requestMap.containsKey("price")) { // avoid status update
     * //
     * //
     * //            if (validateId) {
     * //                return requestMap.containsKey("id") && requestMap.containsKey("status"); // Ensure "id" is present if validateId is true
     * //            }
     * //            return true; // If validateId is false, return true since all required fields are present
     * //        }
     * //        return false;
     * //    }
     */
    // THIS METHOD WILL WORK FOR BOTH UPDATE THE COMPLETE PRODUCT AS WELL TO UPDATE ONLY THE PRODUCT STATUS.
    private boolean validateProductMap(Map<String, String> requestMap, boolean validateId) {
        if (validateId) {
            // For update requests, ensure 'id' and 'status' exist
            return requestMap.containsKey("id") && requestMap.containsKey("status");
        } else {
            // For create requests, ensure 'name', 'description', and 'price' exist
            return requestMap.containsKey("name") && requestMap.containsKey("description") && requestMap.containsKey("price");
        }
    }


    /**
     * the getProductFromMap method will be used for both getting the product map or validating the product
     */
    private Product getProductFromMap(Map<String, String> requestMap, boolean isAdd, Category managedCategory) {
        Category category = managedCategory;

        Product product = new Product();  // creating object of the Product
        if (isAdd && requestMap.containsKey("id")) {
            product.setId(Integer.parseInt(requestMap.get("id")));
        }
        product.setCategory(category);
        product.setName(requestMap.get("name"));
        product.setDescription(requestMap.get("description"));
        product.setPrice(Integer.parseInt(requestMap.get("price")));
        product.setStatus(requestMap.get("status"));
        return product;
    }

    /** Null-safe integer parse for map values (fix for DEFECT_REGISTER P.1: NPE/500 on bad categoryId). */
    private static Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }


    @Override
    public ResponseEntity<List<ProductWrapper>> getAllProduct() {
        try {
            return new ResponseEntity<>(productDao.getAllProduct(), HttpStatus.OK);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new ResponseEntity<>(new ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR);
    }


    @Override
    public ResponseEntity<String> updateProduct(Map<String, String> requestMap) {
        try {
            if (jwtFilter.isAdmin()) {
                // method to check whether the product with id is present in the database or not
                if (validateProductMap(requestMap, true)) {
                    /** The server extracts the product ID from requestMap (sent by the client), converts it to an Integer (since findById() requires a number),
                     and checks if the product exists in productDao. And this will return the object of type Optional so, we will store it into the optional*/
//                    Optional<Product> optional = productDao.findById(Integer.parseInt(requestMap.get("id")));
                    Optional<Product> optional = productDao.findById(parsePositiveInt(requestMap.get("id")));
                    if (optional.isPresent()) {
                        Integer categoryId = parsePositiveInt(requestMap.get("categoryId"));
                        Optional<Category> managedCategory = categoryId == null
                                ? Optional.empty() : categoryDao.findById(categoryId);
                        if (managedCategory.isEmpty()) {
                            return CafeUtils.getResponseEntity("Invalid Data: categoryId must be an id of an existing category", HttpStatus.BAD_REQUEST);
                        }
                        productDao.save(getProductFromMap(requestMap, true, managedCategory.get()));
                        return CafeUtils.getResponseEntity("Product Updated Successfully", HttpStatus.OK);
                    } else {
                        return CafeUtils.getResponseEntity("Product id doesn't exists", HttpStatus.OK);
                    }
                }
                // and if validation is failed then return
                return CafeUtils.getResponseEntity(CafeConstants.INVALID_DATA, HttpStatus.BAD_REQUEST);
            } else {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }



    @Override
    public ResponseEntity<String> deleteProduct(Integer id) {
        try {
            if (jwtFilter.isAdmin()) {
                Optional optional = productDao.findById(id);
                if (!optional.isEmpty()) {
                    productDao.deleteById(id);
                    return CafeUtils.getResponseEntity("Product Deleted Successfully.", HttpStatus.OK);
                }
                return CafeUtils.getResponseEntity("Product id does not exist.", HttpStatus.OK);
            } else {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }


    // Method to update the product status  (updateStatus follows below)
    /**
     * ADDITIVE (admin-only): set/clear the featured (signature) flag. Uses save()
     * on the full entity so the flag co-exists with all existing columns;
     * existing rows' NULL becomes an explicit value ONLY when an admin acts.
     */
    @Transactional
    @Override
    public ResponseEntity<String> setFeatured(Map<String, String> requestMap) {
        try {
            if (jwtFilter.isAdmin()) {
                String id = requestMap.get("id");
                String featured = requestMap.get("featured");
                if (id == null || featured == null || (!"true".equalsIgnoreCase(featured) && !"false".equalsIgnoreCase(featured))) {
                    return CafeUtils.getResponseEntity(CafeConstants.INVALID_DATA, HttpStatus.BAD_REQUEST);
                }
                Optional<Product> optional = productDao.findById(Integer.parseInt(id));
                if (optional.isEmpty()) {
                    return CafeUtils.getResponseEntity("Product id doesn't exists", HttpStatus.OK);
                }
                Product product = optional.get();
                product.setFeatured(Boolean.parseBoolean(featured));
                productDao.save(product);
                return CafeUtils.getResponseEntity("Featured status updated successfully", HttpStatus.OK);
            } else {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity<String> updateStatus(Map<String, String> requestMap) {
        try {
            if (jwtFilter.isAdmin()) {
                if (validateProductMap(requestMap, true)) {
                    Optional<Product> optional = productDao.findById(Integer.parseInt(requestMap.get("id")));
                    if (!optional.isEmpty()) {  // or we can also use if (optional.isPresent()) {
                        // Initialize the updateProductStatus in ProductDao to accept 2 arguments 'status' and 'id' as a parameter and also write query in ProductPOJO class
                        productDao.updateProductStatus(requestMap.get("status"), Integer.parseInt(requestMap.get("id")));
                        return CafeUtils.getResponseEntity("Product Status Updated Successfully", HttpStatus.OK);
                    } else {
                        return CafeUtils.getResponseEntity("Product id doesn't exists", HttpStatus.OK);
                    }
                }
                return CafeUtils.getResponseEntity(CafeConstants.INVALID_DATA, HttpStatus.BAD_REQUEST);
            } else {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }


    @Override
    public ResponseEntity<List<ProductWrapper>> getByCategory(Integer id) {
        try {
            // Initialize the getProductByCategory in ProductDao to accept and argument 'id' as a parameter and also write query in ProductPOJO class
            return new ResponseEntity<>(productDao.getProductByCategory(id), HttpStatus.OK);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new ResponseEntity<>(new ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR);
    }


    // method to get product by id
    @Override
    public ResponseEntity<ProductWrapper> getProductById(Integer id) {
        try {
        return new ResponseEntity<>(productDao.getProductById(id), HttpStatus.OK);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new ResponseEntity<>(new ProductWrapper(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // NOTE (Issue #2 fix): the legacy duplicate Excel implementations that previously lived
    // here (validateAndSaveProducts / processExcelData and their POI helpers) were removed.
    // processExcelData manually assigned product ids from findTopByOrderByIdDesc()+1 which
    // collided with the database auto-increment (root cause of the Excel-vs-DB id mismatch,
    // DEFECT_REGISTER 2.2) and was not reachable from any REST path. The single authoritative
    // implementation is now Service/ExcelService.parseAndImport (id-independent, structured
    // reporting, thread-safe, transactional).


// Pagination implementation
    @Override
    public Page<Product> getAllProductList(Pageable pageable) {
        return productDao.findAll(pageable);
    }

}