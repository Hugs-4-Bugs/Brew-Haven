package com.inn.cafe.RestImpl;

import com.inn.cafe.Rest.InteractionEventRest;
import com.inn.cafe.Service.InteractionEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InteractionEventRestImpl implements InteractionEventRest {

    @Autowired
    private InteractionEventService interactionEventService;

    @Override
    public ResponseEntity<String> record(Map<String, Object> requestMap) { return interactionEventService.record(requestMap); }
}
