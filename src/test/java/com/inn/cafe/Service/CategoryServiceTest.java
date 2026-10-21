package com.inn.cafe.Service;

import com.inn.cafe.DAO.CategoryDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Category;
import com.inn.cafe.ServiceImpl.CategoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CategoryServiceTest — FIXED to assert the ACTUAL backend contract
 * (previously 5/6 tests failed against stale expectations, see DEFECT_REGISTER T.1):
 *  - unauthorized => HTTP 401 with {"message":"Unauthorized access"} (NOT 403)
 *  - add-new-category success => plain-text body "Category Added Successfully"
 *  - update uses the pessimistically LOCKED entity (findByIdWithLock)
 *  - messages are JSON {"message": ...} where the service uses CafeUtils
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @Mock
    private CategoryDao categoryDao;

    @Mock
    private JwtFilter jwtFilter;

    private Map<String, String> requestMap;
    private Category category;

    @BeforeEach
    void setUp() {
        requestMap = new HashMap<>();
        requestMap.put("name", "Beverages");

        category = new Category();
        category.setId(1);
        category.setName("Beverages");
    }

    @Test
    void testAddNewCategory_Success() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        when(categoryDao.existsByNameIgnoreCase("Beverages")).thenReturn(false);
        when(categoryDao.save(Mockito.any(Category.class))).thenReturn(category);

        ResponseEntity<String> response = categoryService.addNewCategory(requestMap);

        assertEquals(HttpStatus.OK.value(), response.getStatusCodeValue());
        assertEquals("Category Added Successfully", response.getBody()); // actual contract: plain text
    }

    @Test
    void testAddNewCategory_Duplicate() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        when(categoryDao.existsByNameIgnoreCase("Beverages")).thenReturn(true);

        ResponseEntity<String> response = categoryService.addNewCategory(requestMap);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCodeValue());
        assertEquals("{\"message\":\"Item Already Exists\"}", response.getBody());
    }

    @Test
    void testAddNewCategory_Unauthorized() {
        when(jwtFilter.isAdmin()).thenReturn(false);

        ResponseEntity<String> response = categoryService.addNewCategory(requestMap);

        // actual contract: 401 (the stale test wrongly expected 403)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCodeValue());
        assertEquals("{\"message\":\"Unauthorized access\"}", response.getBody());
    }

    @Test
    void testAddNewCategory_InvalidPayload_Returns400Not500() {
        // FIX P.2 regression test: payload without 'name' must be 400 Invalid Data, not 500
        when(jwtFilter.isAdmin()).thenReturn(true);
        Map<String, String> bad = new HashMap<>();

        ResponseEntity<String> response = categoryService.addNewCategory(bad);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCodeValue());
        assertEquals("{\"message\":\"Invalid Data\"}", response.getBody());
    }

    @Test
    void testGetAllCategories() {
        List<Category> categoryList = Collections.singletonList(category);
        when(categoryDao.findAll()).thenReturn(categoryList);

        ResponseEntity<List<Category>> response = categoryService.getAllCategory(null);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Beverages", response.getBody().get(0).getName());
    }

    @Test
    void testUpdateCategory_Success() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        // actual contract: update fetches via the PESSIMISTIC LOCK query and mutates it (FIX P.3)
        when(categoryDao.findByIdWithLock(1)).thenReturn(Optional.of(category));

        requestMap.put("id", "1");
        requestMap.put("name", "Updated Beverages");

        ResponseEntity<String> response = categoryService.updateCategory(requestMap);

        assertEquals(HttpStatus.OK.value(), response.getStatusCodeValue());
        assertEquals("{\"message\":\"Category Updated Successfully\"}", response.getBody()); // actual: JSON body
        assertEquals("Updated Beverages", category.getName()); // locked entity was mutated & saved
        verify(categoryDao).save(category);
    }

    @Test
    void testUpdateCategory_NotFound() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        when(categoryDao.findByIdWithLock(1)).thenReturn(Optional.empty());

        requestMap.put("id", "1");
        requestMap.put("name", "Updated Beverages");

        ResponseEntity<String> response = categoryService.updateCategory(requestMap);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCodeValue());
        assertEquals("{\"message\":\"Category id doesn't exist\"}", response.getBody());
    }

    @Test
    void testUpdateCategory_Unauthorized() {
        when(jwtFilter.isAdmin()).thenReturn(false);

        requestMap.put("id", "1");
        requestMap.put("name", "Updated Beverages");

        ResponseEntity<String> response = categoryService.updateCategory(requestMap);

        // actual contract: 401 (the stale test wrongly expected 403)
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCodeValue());
        assertEquals("{\"message\":\"Unauthorized access\"}", response.getBody());
    }
}
