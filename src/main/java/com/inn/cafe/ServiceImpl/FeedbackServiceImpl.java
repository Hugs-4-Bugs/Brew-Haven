package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.DAO.FeedbackDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.Feedback;
import com.inn.cafe.Service.FeedbackService;
import com.inn.cafe.Utils.CafeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Post-order feedback. Ownership is enforced: the bill's email must match the
 * signed-in account, so customers can only rate their own orders. Repeated
 * submission for the same bill UPDATES the row (idempotent).
 */
@Slf4j
@Service
public class FeedbackServiceImpl implements FeedbackService {

    @Autowired FeedbackDao feedbackDao;
    @Autowired BillDao billDao;
    @Autowired JwtFilter jwtFilter;

    @Override
    public ResponseEntity<String> submit(Map<String, Object> requestMap) {
        try {
            String email = jwtFilter.getCurrentUser();
            if (email == null) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            String billUuid = String.valueOf(requestMap.get("billUuid") == null ? "" : requestMap.get("billUuid")).trim();
            Integer rating;
            try {
                rating = Integer.parseInt(String.valueOf(requestMap.get("rating")));
            } catch (Exception ex) {
                return CafeUtils.getResponseEntity("Rating must be a number 1-5", HttpStatus.BAD_REQUEST);
            }
            if (rating < 1 || rating > 5) {
                return CafeUtils.getResponseEntity("Rating must be between 1 and 5", HttpStatus.BAD_REQUEST);
            }
            String comment = requestMap.get("comment") == null ? "" : String.valueOf(requestMap.get("comment")).trim();
            if (comment.length() > 500) {
                return CafeUtils.getResponseEntity("Comment must be at most 500 characters", HttpStatus.BAD_REQUEST);
            }
            if (billUuid.isEmpty()) {
                return CafeUtils.getResponseEntity("billUuid is required", HttpStatus.BAD_REQUEST);
            }
            Optional<Bill> billOpt = billDao.findAll().stream()
                    .filter(b -> billUuid.equals(b.getUuid())).findFirst();
            if (!billOpt.isPresent()) {
                return CafeUtils.getResponseEntity("Bill not found", HttpStatus.BAD_REQUEST);
            }
            Bill bill = billOpt.get();
            if (!email.equalsIgnoreCase(bill.getEmail())) {
                return CafeUtils.getResponseEntity("You can only leave feedback for your own orders", HttpStatus.FORBIDDEN);
            }
            Feedback fb = feedbackDao.findByBillUuid(billUuid);
            if (fb == null) {
                fb = new Feedback();
                fb.setBillUuid(billUuid);
                fb.setCreatedAt(new Date());
            }
            fb.setEmail(email.toLowerCase());
            fb.setRating(rating);
            fb.setComment(comment);
            feedbackDao.save(fb);
            return CafeUtils.getResponseEntity("Thank you for your feedback!", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("feedback submit failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity<Map<String, Object>> getAll() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!jwtFilter.isAdmin()) {
            body.put("message", "Unauthorized Access");
            return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
        }
        List<Map<String, Object>> rows = feedbackDao.findAllByOrderByCreatedAtDesc().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("billUuid", f.getBillUuid());
            m.put("email", f.getEmail());
            m.put("rating", f.getRating());
            m.put("comment", f.getComment());
            m.put("createdAt", f.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        body.put("feedback", rows);
        body.put("count", rows.size());
        double avg = rows.stream().mapToInt(r -> (int) r.get("rating")).average().orElse(0);
        body.put("averageRating", Math.round(avg * 10) / 10.0);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }
}
