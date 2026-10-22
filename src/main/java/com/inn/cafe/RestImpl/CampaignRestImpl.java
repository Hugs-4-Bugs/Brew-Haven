package com.inn.cafe.RestImpl;

import com.inn.cafe.Rest.CampaignRest;
import com.inn.cafe.Service.CampaignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CampaignRestImpl implements CampaignRest {

    @Autowired
    private CampaignService campaignService;

    @Override
    public ResponseEntity<String> create(Map<String, String> requestMap) { return campaignService.create(requestMap); }

    @Override
    public ResponseEntity<Map<String, Object>> list() { return campaignService.list(); }

    @Override
    public ResponseEntity<String> setStatus(Map<String, String> requestMap) { return campaignService.setStatus(requestMap); }

    @Override
    public ResponseEntity<Map<String, Object>> send(Map<String, String> requestMap) { return campaignService.send(requestMap); }

    @Override
    public ResponseEntity<Map<String, Object>> activeForCustomer() { return campaignService.activeForCustomer(); }

    @Override
    public ResponseEntity<String> recordInteraction(Map<String, Object> requestMap) { return campaignService.recordInteraction(requestMap); }
}
