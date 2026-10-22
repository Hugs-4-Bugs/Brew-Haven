package com.inn.cafe.Wrapper;


import lombok.Data;

/**
 * in this class we will specify the columns and key in which we want to return the value
 */

@Data
public class ProductWrapper {
    Integer id;

    String name;

    String description;

    Integer price;

    String status;

    Integer categoryId;

    String categoryName;

    /** Admin-curated signature/featured flag (null = not featured). ADDITIVE. */
    Boolean featured;

    // default constructor
    public ProductWrapper(){

    }

    // constructor for getAllProduct
    public ProductWrapper(Integer id, String name, String description, Integer price, String status, Integer categoryId, String categoryName) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.status = status;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
    }

    // ADDITIVE constructor for getAllProduct incl. the admin-curated featured flag
    // (JSON gains ONE optional field — backward compatible for existing consumers).
    public ProductWrapper(Integer id, String name, String description, Integer price, String status, Integer categoryId, String categoryName, Boolean featured) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.status = status;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.featured = featured;
    }

    // constructor for getProductByCategory
    public ProductWrapper(Integer id, String name){
    this.id = id;
    this.name = name;
    }


    // constructor for getProductById
    public ProductWrapper(Integer id, String name, String description, Integer price){
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;

    }
}
