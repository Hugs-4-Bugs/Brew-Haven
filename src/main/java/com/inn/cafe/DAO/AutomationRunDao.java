package com.inn.cafe.DAO;

import com.inn.cafe.POJO.AutomationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** ADDITIVE DAO — see POJO/AutomationRun. */
public interface AutomationRunDao extends JpaRepository<AutomationRun, Integer> {
    AutomationRun findByDedupeKey(String dedupeKey);
    List<AutomationRun> findTop100ByOrderByIdDesc();
}
