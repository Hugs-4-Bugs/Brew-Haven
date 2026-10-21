package com.inn.cafe.Service;

import com.inn.cafe.CafeManagementSystemApplication;
import com.inn.cafe.POJO.Category;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CategoryServiceIT — integration test against a real database.
 * FIX (DEFECT_REGISTER T.2): the legacy version autowired CategoryServiceTest (a Mockito
 * unit-test class that is not a Spring bean), which would break the application context
 * as soon as the test actually ran (surefire never picked *IT up; failsafe would have).
 */
@SpringBootTest(classes = CafeManagementSystemApplication.class)
@Transactional
public class CategoryServiceIT {

    @Autowired
    private CategoryService categoryService;

    @Test
    void testGetAllCategories() {
        List<Category> categories = categoryService.getAllCategory("false").getBody();
        assertNotNull(categories);
    }
}
