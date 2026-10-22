package com.inn.cafe.Rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequestMapping(path = "/events")
public interface InteractionEventRest {

    /** Body: { type: UPSELL_SHOWN|UPSELL_ACCEPTED|PREF_SAVED|CART_CREATED|CHECKOUT_STARTED|ORDER_COMPLETED, productId?, detail? }. */
    @PostMapping(path = "/record")
    ResponseEntity<String> record(@RequestBody Map<String, Object> requestMap);
}
