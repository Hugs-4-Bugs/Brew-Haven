package com.inn.cafe.DAO;

import com.inn.cafe.POJO.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** ADDITIVE DAO — see POJO/Campaign. */
public interface CampaignDao extends JpaRepository<Campaign, Integer> {
    Optional<Campaign> findByCodeIgnoreCase(String code);
    List<Campaign> findAllByOrderByIdDesc();
    List<Campaign> findByStatusOrderByIdDesc(String status);
}
