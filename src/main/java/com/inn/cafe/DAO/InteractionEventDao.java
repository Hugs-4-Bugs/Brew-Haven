package com.inn.cafe.DAO;

import com.inn.cafe.POJO.InteractionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** ADDITIVE DAO — see POJO/InteractionEvent. */
public interface InteractionEventDao extends JpaRepository<InteractionEvent, Integer> {
    List<InteractionEvent> findByEventType(String eventType);
    List<InteractionEvent> findByEventTypeAndProductId(String eventType, Integer productId);
    List<InteractionEvent> findAllByOrderByIdDesc();
}
