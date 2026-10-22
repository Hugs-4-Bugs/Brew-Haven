package com.inn.cafe.DAO;

import com.inn.cafe.POJO.CampaignEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** ADDITIVE DAO — see POJO/CampaignEvent. */
public interface CampaignEventDao extends JpaRepository<CampaignEvent, Integer> {
    List<CampaignEvent> findByCampaignIdOrderByIdAsc(Integer campaignId);
    List<CampaignEvent> findByEventType(String eventType);
    List<CampaignEvent> findByCampaignIdAndEventType(Integer campaignId, String eventType);
    List<CampaignEvent> findByCampaignIdAndEventTypeAndBillId(Integer campaignId, String eventType, Integer billId);
    long countByCampaignIdAndEventType(Integer campaignId, String eventType);
}
