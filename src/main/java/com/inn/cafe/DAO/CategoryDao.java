package com.inn.cafe.DAO;

import com.inn.cafe.POJO.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;


@Repository
public interface CategoryDao extends JpaRepository<Category, Integer> {

    // query to fetch the data (which is get all products from category)
    List<Category> getAllCategory();

    boolean existsByName(String name); // This checks if a category with the given name exists (implemented by Prabhat)

    // FIX P.2: case-insensitive duplicate check for category creation
    boolean existsByNameIgnoreCase(String name);


//    Category findByName(String name);


    // ✅ Change return type from `Category` to `Optional<Category>` to handle null cases
    Optional<Category> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Category c WHERE c.id = :id")
    Optional<Category> findByIdWithLock(@Param("id") Integer id);


    @Query("SELECT c.id FROM Category c")
    List<Integer> findAllCategoryIds();


}
