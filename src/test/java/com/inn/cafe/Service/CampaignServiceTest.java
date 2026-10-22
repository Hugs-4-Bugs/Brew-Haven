package com.inn.cafe.Service;

import com.inn.cafe.DAO.BillDao;
import com.inn.cafe.DAO.CampaignDao;
import com.inn.cafe.DAO.CampaignEventDao;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.POJO.Bill;
import com.inn.cafe.POJO.Campaign;
import com.inn.cafe.POJO.CampaignEvent;
import com.inn.cafe.ServiceImpl.CampaignServiceImpl;
import com.inn.cafe.Utils.CafeUtils;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CampaignServiceTest — campaign lifecycle, honest no-provider send semantics
 * (LogChannel records, never fakes), and server-verified attribution.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    @InjectMocks
    private CampaignServiceImpl campaignService;

    @Mock private BillDao billDao;
    @Mock private CampaignDao campaignDao;
    @Mock private CampaignEventDao campaignEventDao;
    @Mock private JwtFilter jwtFilter;

    /** Always-configured recording channel standing in for the LogChannel. */
    public static class RecordingChannel implements NotificationChannel {
        final List<String> sentTo = new ArrayList<>();
        public String id() { return "NONE"; }
        public boolean configured() { return true; }
        public void send(String to, String subject, String body) { sentTo.add(to); }
    }

    @Spy
    private RecordingChannel channel = new RecordingChannel();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(campaignService, "channels", new ArrayList<>(List.of(channel)));
        ReflectionTestUtils.setField(campaignService, "highValueThreshold", 1000);
    }

    private Campaign active(int id, String segment, Integer usageLimit) {
        Campaign c = new Campaign();
        c.setId(id);
        c.setName("Monsoon offer");
        c.setCode("BREW" + id);
        c.setStatus(Campaign.STATUS_ACTIVE);
        c.setSegment(segment);
        c.setUsageLimit(usageLimit);
        return c;
    }

    @Test
    void create_isAdminOnly_andValidatesInput() {
        when(jwtFilter.isAdmin()).thenReturn(false);
        assertEquals(401, campaignService.create(Map.of("name", "X")).getStatusCodeValue());

        when(jwtFilter.isAdmin()).thenReturn(true);
        assertEquals(400, campaignService.create(Map.of()).getStatusCodeValue()); // no name
        assertEquals(400, campaignService.create(Map.of("name", "X", "segment", "BOGUS")).getStatusCodeValue());
        assertEquals(400, campaignService.create(Map.of("name", "X", "startsAt", "31-12-2026")).getStatusCodeValue());
    }

    @Test
    void create_generatesDeterministicServerSideCode() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        when(jwtFilter.getCurrentUser()).thenReturn("admin@cafe.local");
        when(campaignDao.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            if (c.getId() == null) c.setId(12);
            return c;
        });

        ResponseEntity<String> resp = campaignService.create(Map.of("name", "Monsoon offer", "segment", "ALL"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("BREW12"));
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignDao, atLeastOnce()).save(captor.capture());
        assertEquals(Campaign.STATUS_DRAFT, captor.getValue().getStatus());
    }

    @Test
    void send_recordsEventsHonestly_whenChannelIsRecordingOnly() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        when(jwtFilter.getCurrentUser()).thenReturn("admin@cafe.local");
        Campaign c = active(3, "ALL", null);
        when(campaignDao.findById(3)).thenReturn(Optional.of(c));
        when(campaignEventDao.countByCampaignIdAndEventType(3, CampaignEvent.TYPE_SENT)).thenReturn(0L);

        // Two real bill-backed customers (ALL = distinct bill emails minus the acting admin).
        Bill b1 = new Bill(); b1.setEmail("a@x"); b1.setUuid("BILL-" + System.currentTimeMillis());
        Bill b2 = new Bill(); b2.setEmail("b@x"); b2.setUuid("BILL-" + System.currentTimeMillis());
        when(billDao.findAll()).thenReturn(new ArrayList<>(List.of(b1, b2)));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> resp = campaignService.send(Map.of("id", "3"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().get("sent"));
        assertEquals("NONE", resp.getBody().get("channel"));
        assertTrue(String.valueOf(resp.getBody().get("note")).contains("No delivery provider"));
        assertEquals(List.of("a@x", "b@x"), channel.sentTo);
        verify(campaignEventDao, times(2)).save(any(CampaignEvent.class));
    }

    @Test
    void send_refusesDraftCampaigns_andRespectsUsageLimits() {
        when(jwtFilter.isAdmin()).thenReturn(true);
        Campaign draft = active(4, "ALL", null);
        draft.setStatus(Campaign.STATUS_DRAFT);
        when(campaignDao.findById(4)).thenReturn(Optional.of(draft));
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> resp = campaignService.send(Map.of("id", "4"));
        assertEquals(400, resp.getStatusCodeValue());

        Campaign limited = active(5, "ALL", 2);
        when(campaignDao.findById(5)).thenReturn(Optional.of(limited));
        when(campaignEventDao.countByCampaignIdAndEventType(5, CampaignEvent.TYPE_SENT)).thenReturn(2L);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> resp2 = campaignService.send(Map.of("id", "5"));
        assertEquals(0, resp2.getBody().get("sent"));
        assertEquals(0, resp2.getBody().get("skippedByLimit")); // limit already reached before this send
    }

    @Test
    void attribution_isServerVerifiedAndIdempotentPerBill() {
        Campaign c = active(6, "ALL", null);
        when(campaignDao.findByCodeIgnoreCase("BREW6")).thenReturn(Optional.of(c));
        when(jwtFilter.getCurrentUser()).thenReturn("customer@x");
        when(campaignEventDao.countByCampaignIdAndEventType(6, CampaignEvent.TYPE_CONVERTED)).thenReturn(0L);
        when(campaignEventDao.findByCampaignIdAndEventTypeAndBillId(6, CampaignEvent.TYPE_CONVERTED, 99))
                .thenReturn(Collections.emptyList());

        Campaign attributed = campaignService.attributeConversion("BREW6", 99, 447, new StringBuilder());
        assertNotNull(attributed);

        ArgumentCaptor<CampaignEvent> captor = ArgumentCaptor.forClass(CampaignEvent.class);
        verify(campaignEventDao).save(captor.capture());
        assertEquals(CampaignEvent.TYPE_CONVERTED, captor.getValue().getEventType());
        assertEquals(447, captor.getValue().getRevenue());
        assertEquals(99, captor.getValue().getBillId());

        // Re-attribution for the same bill is a no-op (no second CONVERTED row).
        when(campaignEventDao.findByCampaignIdAndEventTypeAndBillId(6, CampaignEvent.TYPE_CONVERTED, 99))
                .thenReturn(List.of(captor.getValue()));
        campaignService.attributeConversion("BREW6", 99, 447, new StringBuilder());
        verify(campaignEventDao, times(1)).save(any(CampaignEvent.class));
    }

    @Test
    void attribution_rejectsInvalidCodesWithReasons() {
        when(campaignDao.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        StringBuilder reason = new StringBuilder();
        assertNull(campaignService.attributeConversion("NOPE", 1, 100, reason));
        assertEquals("unknown campaign code", reason.toString());

        Campaign inactive = active(8, "ALL", null);
        inactive.setStatus(Campaign.STATUS_COMPLETED);
        when(campaignDao.findByCodeIgnoreCase("BREW8")).thenReturn(Optional.of(inactive));
        StringBuilder reason2 = new StringBuilder();
        assertNull(campaignService.attributeConversion("BREW8", 1, 100, reason2));
        assertEquals("campaign is not active", reason2.toString());
    }

    @Test
    void activeForCustomer_hidesDraftExpiredAndExhaustedCampaigns() {
        Campaign draft = active(10, "ALL", null); draft.setStatus(Campaign.STATUS_DRAFT);
        Campaign good = active(11, "ALL", null);
        Campaign exhausted = active(12, "ALL", 1);
        when(campaignDao.findByStatusOrderByIdDesc(Campaign.STATUS_ACTIVE)).thenReturn(new ArrayList<>(List.of(good, exhausted)));
        when(campaignEventDao.countByCampaignIdAndEventType(11, CampaignEvent.TYPE_CONVERTED)).thenReturn(0L);
        when(campaignEventDao.countByCampaignIdAndEventType(12, CampaignEvent.TYPE_CONVERTED)).thenReturn(1L);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> resp = campaignService.activeForCustomer();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("campaigns");
        assertEquals(1, rows.size());
        assertEquals("BREW11", rows.get(0).get("code"));
    }
}
