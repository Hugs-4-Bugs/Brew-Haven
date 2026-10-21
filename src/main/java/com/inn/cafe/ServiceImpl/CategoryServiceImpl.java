package com.inn.cafe.ServiceImpl;

import com.google.common.base.Strings;
import com.inn.cafe.Constents.CafeConstants;
import com.inn.cafe.DAO.CategoryDao;
import com.inn.cafe.DAO.ProductDao;
import com.inn.cafe.POJO.Product;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Category;
import com.inn.cafe.Service.CategoryService;
import com.inn.cafe.Utils.CafeUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.OptimisticLockException;
import javax.transaction.Transactional;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Slf4j
@Service
public class CategoryServiceImpl implements CategoryService {

    @Autowired
    private CategoryDao categoryDao;

    @Autowired
    private ProductDao productDao;

    @Autowired
    private JwtFilter jwtFilter;  // to check the current user is admin because we have method for admin in JwtFilter class, so we are creating the bean of JwtFilter class here.

    @Override
    public ResponseEntity<String> addNewCategory(Map<String, String> requestMap) {
        try {
            // only admin can add the category, so first we will check whether the current user is admin
            if (jwtFilter.isAdmin()) {
                if (validateCategoryMap(requestMap, false)) {
                    String categoryName = requestMap.get("name") == null ? "" : requestMap.get("name").trim();
                    if (categoryName.isEmpty()) {
                        return CafeUtils.getResponseEntity("Item cannot be empty", HttpStatus.BAD_REQUEST);
                    }
                    // FIX P.2: case-insensitive duplicate prevention ("Drinks" vs "drinks" are the same category)
                    if (categoryDao.existsByNameIgnoreCase(categoryName)) {
                        return CafeUtils.getResponseEntity("Item Already Exists", HttpStatus.BAD_REQUEST);
                    }
                    categoryDao.save(getCategoryFromMap(requestMap, false));
//                    return CafeUtils.getResponseEntity("Category Added Successfully", HttpStatus.OK);
                    return ResponseEntity.ok("Category Added Successfully");
                }
                // FIX P.2: invalid payload previously fell through to 500 "Something Went Wrong"
                return CafeUtils.getResponseEntity(CafeConstants.INVALID_DATA, HttpStatus.BAD_REQUEST);
            } else {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception ex) {
            log.error("Category operation failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }


    /**
     * The `validateCategoryMap` method checks if the request has a `name` and, if needed, an `id` to make sure the data is correct before adding or updating a category.
     */
    private boolean validateCategoryMap(Map<String, String> requestMap, boolean validateId) {
        if (requestMap.containsKey("name")) {
            if (requestMap.containsKey("id") && validateId) {
                return true;
            } else if (!validateId) {
                return true;
            }
        }
        return false;
    }

    /**
     * This method takes data from the request and creates a Category object. If it's a new category, it just sets the name.
     * If it's an update, it also sets the ID. This helps in saving or updating categories properly.
     */
    private Category getCategoryFromMap(Map<String, String> requestMap, Boolean isAdd) {
        Category category = new Category();  // object of Category
        if (isAdd) {
            category.setId(Integer.parseInt(requestMap.get("id")));
        }
        category.setName(requestMap.get("name"));
        return category;
    }


    /**
     * API to get all the items by admin only if you use the url: http://localhost:8000/category/get?filterValue=true
     * and if u use http://localhost:8000/category/get it will list all the items by normal user
     */
    @Override
    public ResponseEntity<List<Category>> getAllCategory(String filterValue) {
        try {
            // this will return those value which contains one or more product in that (ie. the category list in which entry is not null)
            if (!Strings.isNullOrEmpty(filterValue) && filterValue.equalsIgnoreCase("true")) {
                log.info("Inside if");
                return new ResponseEntity<List<Category>>(categoryDao.getAllCategory(), HttpStatus.OK);
            }
            return new ResponseEntity<>(categoryDao.findAll(), HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Category operation failed: {}", ex.getMessage());
        }
        return new ResponseEntity<List<Category>>(new ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR);
    }


    /**
     * FIX (found in E2E): findByIdWithLock uses a PESSIMISTIC_WRITE lock which REQUIRES an
     * active transaction — without @Transactional the update failed with
     * TransactionRequiredException ("no transaction is in progress"), i.e. category
     * rename never worked via REST (unit tests mocked the DAO and missed it).
     */
    @Transactional
    @Override
    public ResponseEntity<String> updateCategory(Map<String, String> requestMap) {
        try {
            if (jwtFilter.isAdmin()) {  // if user is admin
                // method to check whether the category with id is present in the database or not
                if (validateCategoryMap(requestMap, true)) {  // true means we have validated the id also (see the implementation of above validateCategoryMap method)
                    /** The server extracts the category ID from requestMap (sent by the client), converts it to an Integer (since findById() requires a number),
                     and checks if the category exists in categoryDao. And this will return the object of type Optional so, we will store it into the optional*/
//                    Optional optional = categoryDao.findById(Integer.parseInt(requestMap.get("id")));
                    Optional<Category> optional = categoryDao.findByIdWithLock(Integer.parseInt(requestMap.get("id")));
                      if(optional.isPresent()){ // or  if (!optional.isEmpty()) {
                        // FIX P.3: mutate the LOCKED managed entity so the pessimistic lock and the
                        // @Version field are actually honoured (the legacy code discarded the locked
                        // entity and merged a detached new Category(id,name) with version=null).
                        Category locked = optional.get();
                        String newName = requestMap.get("name") == null ? locked.getName() : requestMap.get("name").trim();
                        if (newName.isEmpty()) {
                            return CafeUtils.getResponseEntity("Item cannot be empty", HttpStatus.BAD_REQUEST);
                        }
                        locked.setName(newName);
                        categoryDao.save(locked);
                        return CafeUtils.getResponseEntity("Category Updated Successfully", HttpStatus.OK);
                    } else {
                        return CafeUtils.getResponseEntity("Category id doesn't exist", HttpStatus.BAD_REQUEST);
                    }
                }
                // and if validation is failed then return
                return CafeUtils.getResponseEntity(CafeConstants.INVALID_DATA, HttpStatus.BAD_REQUEST);
            } else {
                return CafeUtils.getResponseEntity(CafeConstants.UNAUTHOROZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception ex) {
            log.error("Category operation failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }


    // Optimistic Locking implementation
    @Transactional
    public Category updateCategory(Category category) {
        try {
            return categoryDao.save(category);
        } catch (OptimisticLockException e) {
            throw new RuntimeException("Conflict detected! Category was updated by another transaction.", e);
        }
    }


    // Pessimistic Locking implementation

    /**
     * @Transactional public Category updateCategoryWithLock(int id, String newName) {
     * Optional<Category> optionalCategory = categoryDao.findByIdWithLock(id);  // Use pessimistic locking
     * <p>
     * if (optionalCategory.isPresent()) {  // Ensure the category exists
     * Category category = optionalCategory.get(); // Extract Category from Optional
     * category.setName(newName);  // Update the name
     * return categoryDao.save(category);  // Save the updated category
     * } else {
     * throw new EntityNotFoundException("Category with ID " + id + " not found");
     * }
     * }
     */
    @Transactional
    public ResponseEntity<String> updateCategoryWithLock(int id, String newName) {
        Category category = categoryDao.getReferenceById(id); // Throws EntityNotFoundException if not found
        category.setName(newName);
        categoryDao.save(category);
        return ResponseEntity.ok("Category updated successfully");
    }


}
