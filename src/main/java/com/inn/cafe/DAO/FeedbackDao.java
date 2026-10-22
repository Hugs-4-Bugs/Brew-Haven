package com.inn.cafe.DAO;

import com.inn.cafe.POJO.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** ADDITIVE DAO — see POJO/Feedback. */
public interface FeedbackDao extends JpaRepository<Feedback, Integer> {
    Feedback findByBillUuid(String billUuid);
    List<Feedback> findAllByOrderByCreatedAtDesc();
}
