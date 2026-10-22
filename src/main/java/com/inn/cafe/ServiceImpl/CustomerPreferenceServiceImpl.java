package com.inn.cafe.ServiceImpl;

import com.inn.cafe.DAO.CustomerPreferenceDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.CustomerPreference;
import com.inn.cafe.Service.CustomerPreferenceService;
import com.inn.cafe.Utils.CafeUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Server-side preference persistence for signed-in customers. The payload is
 * the frontend's own versioned JSON document; the service validates that it
 * parses as JSON and caps its size (16 KB) — it does not interpret or forward
 * health notes anywhere (see health-safety rules).
 */
@Slf4j
@Service
public class CustomerPreferenceServiceImpl implements CustomerPreferenceService {

    private static final int MAX_PAYLOAD = 16 * 1024;

    @Autowired CustomerPreferenceDao dao;
    @Autowired JwtFilter jwtFilter;

    @Override
    public ResponseEntity<String> getMyPreferences() {
        try {
            String email = jwtFilter.getCurrentUser();
            if (email == null) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            CustomerPreference pref = dao.findByEmail(email.toLowerCase());
            if (pref == null) {
                return CafeUtils.getResponseEntity("{\"preferences\":null}", HttpStatus.OK);
            }
            JSONObject body = new JSONObject();
            body.put("preferences", new JSONObject(pref.getPreferences()));
            body.put("version", pref.getVersion());
            return new ResponseEntity<>(body.toString(), HttpStatus.OK);
        } catch (Exception ex) {
            log.error("getMyPreferences failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity<String> saveMyPreferences(String payload, Integer version) {
        try {
            String email = jwtFilter.getCurrentUser();
            if (email == null) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            if (payload == null || payload.trim().isEmpty()) {
                return CafeUtils.getResponseEntity("Preferences payload required", HttpStatus.BAD_REQUEST);
            }
            if (payload.length() > MAX_PAYLOAD) {
                return CafeUtils.getResponseEntity("Preferences payload too large", HttpStatus.BAD_REQUEST);
            }
            try {
                new JSONObject(payload); // must be valid JSON
            } catch (Exception ex) {
                return CafeUtils.getResponseEntity("Preferences must be a valid JSON document", HttpStatus.BAD_REQUEST);
            }
            String key = email.toLowerCase();
            CustomerPreference pref = dao.findByEmail(key);
            if (pref == null) {
                pref = new CustomerPreference();
                pref.setEmail(key);
            }
            pref.setPreferences(payload);
            pref.setVersion(version == null ? 1 : version);
            pref.setUpdatedAt(new Date());
            dao.save(pref);
            return CafeUtils.getResponseEntity("Preferences saved", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("saveMyPreferences failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity<String> clearMyPreferences() {
        try {
            String email = jwtFilter.getCurrentUser();
            if (email == null) {
                return CafeUtils.getResponseEntity("Unauthorized Access", HttpStatus.UNAUTHORIZED);
            }
            CustomerPreference pref = dao.findByEmail(email.toLowerCase());
            if (pref != null) {
                dao.delete(pref);
            }
            return CafeUtils.getResponseEntity("Preferences cleared", HttpStatus.OK);
        } catch (Exception ex) {
            log.error("clearMyPreferences failed: {}", ex.getMessage());
        }
        return CafeUtils.getResponseEntity("Something Went Wrong", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
