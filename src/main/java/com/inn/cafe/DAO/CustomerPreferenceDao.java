package com.inn.cafe.DAO;

import com.inn.cafe.POJO.CustomerPreference;
import org.springframework.data.jpa.repository.JpaRepository;

/** ADDITIVE DAO — see POJO/CustomerPreference. */
public interface CustomerPreferenceDao extends JpaRepository<CustomerPreference, Integer> {
    CustomerPreference findByEmail(String email);
}
