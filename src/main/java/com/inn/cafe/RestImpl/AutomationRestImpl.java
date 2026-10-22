package com.inn.cafe.RestImpl;

import com.inn.cafe.Rest.AutomationRest;
import com.inn.cafe.Service.AutomationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AutomationRestImpl implements AutomationRest {

    @Autowired
    private AutomationService automationService;

    @Override
    public ResponseEntity<String> thankYou(Map<String, String> requestMap) { return automationService.thankYou(requestMap); }

    @Override
    public ResponseEntity<String> runRule(Map<String, String> requestMap) { return automationService.runRule(requestMap); }

    @Override
    public ResponseEntity<Map<String, Object>> runs() { return automationService.runs(); }

    @Override
    public ResponseEntity<Map<String, Object>> status() { return automationService.status(); }
}
