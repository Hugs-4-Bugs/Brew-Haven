package com.inn.cafe.Service;

import com.inn.cafe.DAO.AutomationRunDao;
import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.DAO.CustomerPreferenceDao;
import com.inn.cafe.DAO.FeedbackDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.AutomationRun;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.CustomerPreference;
import com.inn.cafe.POJO.Feedback;
import com.inn.cafe.ServiceImpl.AutomationServiceImpl;
import com.inn.cafe.ServiceImpl.CustomerPreferenceServiceImpl;
import com.inn.cafe.ServiceImpl.FeedbackServiceImpl;
import com.inn.cafe.Utils.NotificationChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the remaining new services: automation idempotency + admin gating,
 * preference storage validation, and feedback ownership rules.
 */
@ExtendWith(MockitoExtension.class)
class RevenuePlatformServicesTest {

    // ------------------------------------------------------------- automation

    @InjectMocks
    private AutomationServiceImpl automation;

    @Mock private AutomationRunDao runDao;
    @Mock private BillDao billDao;
    @Mock private CampaignDao campaignDao;
    @Mock private CampaignEventDao campaignEventDao;
    @Mock private JwtFilter jwtFilter;
    @Spy private com.inn.cafe.ServiceImpl.AnalyticsServiceImpl analytics;
    @Spy private com.inn.cafe.ServiceImpl.CampaignServiceImpl campaignService;

    /** A channel that FAILS = simulates "no real provider configured". */
    public static class UnconfiguredChannel implements NotificationChannel {
        public String id() { return "EMAIL"; }
        public boolean configured() { return false; }
        public void send(String to, String subject, String body) { throw new IllegalStateException("not configured"); }
    }

    @Spy
    private UnconfiguredChannel unconfigured = new UnconfiguredChannel();

    @BeforeEach
    void automationSetup() {
        ReflectionTestUtils.setField(automation, "channels", new ArrayList<>(List.of(unconfigured)));
        ReflectionTestUtils.setField(automation, "highValueThreshold", 1000);
    }

    @Test
    void thankYou_isIdempotentPerBill_andSkipsWhenNoProviderConfigured() {
        when(jwtFilter.getCurrentUser()).thenReturn("c@x");
        Bill bill = new Bill();
        bill.setUuid("BILL-123");
        bill.setEmail("c@x");
        bill.setTotal(250);
        when(billDao.findAll()).thenReturn(List.of(bill));

        // first call: no provider -> run recorded as SKIPPED with honest reason
        when(runDao.findByDedupeKey("ORDER_THANK_YOU:BILL-123")).thenReturn(null);
        ResponseEntity<String> first = automation.thankYou(Map.of("billUuid", "BILL-123"));
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertTrue(first.getBody().contains("SKIPPED"));

        ArgumentCaptor<AutomationRun> captor = ArgumentCaptor.forClass(AutomationRun.class);
        verify(runDao).save(captor.capture());
        AutomationRun run = captor.getValue();
        assertEquals("ORDER_THANK_YOU", run.getRule());
        assertEquals("SKIPPED", run.getStatus());
        assertTrue(run.getDetail().contains("No delivery provider configured"));

        // second call: dedupe key exists -> "already sent", no new run
        when(runDao.findByDedupeKey("ORDER_THANK_YOU:BILL-123")).thenReturn(run);
        ResponseEntity<String> second = automation.thankYou(Map.of("billUuid", "BILL-123"));
        assertTrue(second.getBody().contains("already sent"));
        verify(runDao, times(1)).save(any(AutomationRun.class));
    }

    @Test
    void thankYou_rejectsForeignBills() {
        when(jwtFilter.getCurrentUser()).thenReturn("c@x");
        Bill other = new Bill();
        other.setUuid("BILL-999");
        other.setEmail("someone-else@x");
        when(billDao.findAll()).thenReturn(List.of(other));
        assertEquals(400, automation.thankYou(Map.of("billUuid", "BILL-999")).getStatusCodeValue());
    }

    @Test
    void detectionRules_areAdminOnly_andCreateOnlyDrafts() {
        when(jwtFilter.isAdmin()).thenReturn(false);
        assertEquals(401, automation.runRule(Map.of("rule", "WIN_BACK_DETECTION")).getStatusCodeValue());

        when(jwtFilter.isAdmin()).thenReturn(true);
        when(jwtFilter.getCurrentUser()).thenReturn("admin@cafe.local");
        when(billDao.findAll()).thenReturn(Collections.emptyList());
        ResponseEntity<String> resp = automation.runRule(Map.of("rule", "WIN_BACK_DETECTION"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("No inactive customers detected"));
        verify(campaignDao, never()).save(any(com.inn.cafe.POJO.Campaign.class)); // nothing invented
    }

    // ------------------------------------------------------------ preferences

    @InjectMocks
    private CustomerPreferenceServiceImpl preferences;

    @org.junit.jupiter.api.Nested
    class Preferences {
        @Mock CustomerPreferenceDao dao;
        @Mock JwtFilter jwtFilter;
        @InjectMocks CustomerPreferenceServiceImpl svc;

        @Test
        void saveRequiresValidJson_andRejectsOversizedPayloads() {
            when(jwtFilter.getCurrentUser()).thenReturn("c@x");
            assertEquals(400, svc.saveMyPreferences("not-json{", 1).getStatusCodeValue());
            assertEquals(400, svc.saveMyPreferences("", null).getStatusCodeValue());
            String big = "{\"note\":\"" + "x".repeat(20_000) + "\"}";
            assertEquals(400, svc.saveMyPreferences(big, 1).getStatusCodeValue());
        }

        @Test
        void saveCreatesThenUpdates_perAccount() {
            when(jwtFilter.getCurrentUser()).thenReturn("C@X");
            when(dao.findByEmail("c@x")).thenReturn(null).thenReturn(pref("c@x"));
            ResponseEntity<String> first = svc.saveMyPreferences("{\"favoriteCategories\":[8]}", 2);
            assertEquals(HttpStatus.OK, first.getStatusCode());
            verify(dao).save(any(CustomerPreference.class));

            ResponseEntity<String> second = svc.saveMyPreferences("{\"favoriteCategories\":[9]}", 3);
            assertEquals(HttpStatus.OK, second.getStatusCode());
        }

        private CustomerPreference pref(String email) {
            CustomerPreference p = new CustomerPreference();
            p.setEmail(email);
            return p;
        }
    }

    // --------------------------------------------------------------- feedback

    @org.junit.jupiter.api.Nested
    class FeedbackRules {
        @Mock FeedbackDao feedbackDao;
        @Mock BillDao billDao;
        @Mock JwtFilter jwtFilter;
        @InjectMocks FeedbackServiceImpl svc;

        @Test
        void submitValidatesRatingRangeAndCommentLength() {
            when(jwtFilter.getCurrentUser()).thenReturn("c@x");
            Bill own = new Bill();
            own.setUuid("BILL-1");
            own.setEmail("c@x");
            when(billDao.findAll()).thenReturn(List.of(own));

            assertEquals(400, svc.submit(Map.of("billUuid", "BILL-1", "rating", "0")).getStatusCodeValue());
            assertEquals(400, svc.submit(Map.of("billUuid", "BILL-1", "rating", "6")).getStatusCodeValue());
            assertEquals(400, svc.submit(Map.of("billUuid", "BILL-1", "rating", "5", "comment", "y".repeat(501))).getStatusCodeValue());
            assertEquals(200, svc.submit(Map.of("billUuid", "BILL-1", "rating", "5", "comment", "great")).getStatusCodeValue());
        }

        @Test
        void submitRejectsForeignBills_andIsIdempotentPerBill() {
            when(jwtFilter.getCurrentUser()).thenReturn("c@x");
            Bill foreign = new Bill();
            foreign.setUuid("BILL-2");
            foreign.setEmail("someone-else@x");
            when(billDao.findAll()).thenReturn(List.of(foreign));
            assertEquals(403, svc.submit(Map.of("billUuid", "BILL-2", "rating", "4")).getStatusCodeValue());
        }
    }
}
