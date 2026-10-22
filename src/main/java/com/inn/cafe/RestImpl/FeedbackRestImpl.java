package com.inn.cafe.RestImpl;

import com.inn.cafe.Rest.FeedbackRest;
import com.inn.cafe.Service.FeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FeedbackRestImpl implements FeedbackRest {

    @Autowired
    private FeedbackService feedbackService;

    @Override
    public ResponseEntity<String> submit(Map<String, Object> requestMap) { return feedbackService.submit(requestMap); }

    @Override
    public ResponseEntity<Map<String, Object>> getAll() { return feedbackService.getAll(); }
}
