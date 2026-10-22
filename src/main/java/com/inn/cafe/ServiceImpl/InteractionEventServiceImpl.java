package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.InteractionEventDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.InteractionEvent;
import com.inn.cafe.Service.InteractionEventService;
import com.inn.cafe.Utils.CafeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records client-reported guest-experience events (upsell shown/accepted,
 * cart funnel, pref saved) from SIGNED-IN sessions, with a simple in-memory
 * per-account rate limit so the endpoint cannot be abused. Events power the
 * upsell + funnel analytics; every surface consuming them labels them as
 * client-reported signals.
 */
@Slf4j
@Service
public class InteractionEventServiceImpl implements InteractionEventService {

    private static final Set<String> VALID_TYPES = new HashSet<>(Arrays.asList(
            "UPSELL_SHOWN", "UPSELL_ACCEPTED", "PREF_SAVED", "CART_CREATED",
            "CHECKOUT_STARTED", "ORDER_COMPLETED"));

    private static final int MAX_EVENTS_PER_MINUTE = 60;

    /** email -> window start millis + count (single rolling minute). */
    private final Map<String, int[]> rate = new ConcurrentHashMap<>();

    @Autowired InteractionEventDao dao;
    @Autowired JwtFilter jwtFilter;

    @Override
    public ResponseEntity<String> record(Map<String, Object> requestMap) {
        try {
            String email = jwtFilter.getCurrentUser();
            if (email == null) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            String type = String.valueOf(requestMap.get("type") == null ? "" : requestMap.get("type")).trim().toUpperCase();
            if (!VALID_TYPES.contains(type)) {
                return CafeUtils.getResponseEntity("Unsupported event type", HttpStatus.BAD_REQUEST);
            }
            if (!allow(email)) {
                return new ResponseEntity<String>("{\"message\":\"rate limited\"}", HttpStatus.TOO_MANY_REQUESTS);
            }
            Integer productId = null;
            Object pid = requestMap.get("productId");
            if (pid != null && !String.valueOf(pid).trim().isEmpty()) {
                try { productId = Integer.parseInt(String.valueOf(pid).trim()); } catch (Exception ignored) { }
            }
            String detail = requestMap.get("detail") == null ? null
                    : String.valueOf(requestMap.get("detail")).replaceAll("[\\r\\n]", " ");
            if (detail != null && detail.length() > 300) detail = detail.substring(0, 300);

            InteractionEvent e = new InteractionEvent();
            e.setEventType(type);
            e.setProductId(productId);
            e.setEmail(email.toLowerCase());
            e.setDetail(detail);
            e.setCreatedAt(new Date());
            dao.save(e);
            return new ResponseEntity<String>("{\"message\":\"recorded\"}", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("event record failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private boolean allow(String email) {
        long now = System.currentTimeMillis();
        int[] window = rate.computeIfAbsent(email, k -> new int[]{(int) now, 0});
        synchronized (window) {
            if (now - window[0] > 60_000L) {
                window[0] = (int) now;
                window[1] = 0;
            }
            window[1]++;
            return window[1] <= MAX_EVENTS_PER_MINUTE;
        }
    }
}
