package com.inn.cafe.Rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequestMapping(path = "/feedback")
public interface FeedbackRest {

    /** Body: { billUuid, rating (1-5), comment? } — own bills only. */
    @PostMapping(path = "/submit")
    ResponseEntity<String> submit(@RequestBody Map<String, Object> requestMap);

    @GetMapping(path = "/all")
    ResponseEntity<Map<String, Object>> getAll();
}
