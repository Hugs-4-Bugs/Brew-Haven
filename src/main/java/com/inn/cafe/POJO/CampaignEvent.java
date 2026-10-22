package com.inn.cafe.POJO;

import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * Lifecycle event of a campaign for ONE recipient — the audit trail that
 * powers honest revenue attribution:
 *
 *   SENT      — delivery attempted through a channel (channel records EMAIL or NONE)
 *   VIEWED    — customer saw the banner (client-reported, authed sessions only)
 *   CLICKED   — customer clicked the banner
 *   CONVERTED — a bill was placed with this campaign's code (revenue recorded)
 *
 * Revenue attribution is ONLY ever claimed from CONVERTED rows, which are
 * created exclusively inside the bill-generation flow after the server has
 * verified the campaign code. Interaction events (VIEWED/CLICKED) are
 * client-reported signals and are labelled as such in the analytics.
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "campaign_event",
        indexes = {
                @Index(name = "idx_camp_event_campaign", columnList = "campaign_id"),
                @Index(name = "idx_camp_event_type", columnList = "event_type")
        })
public class CampaignEvent implements Serializable {
    private static final long serialVersionUID = 78104L;

    public static final String TYPE_SENT = "SENT";
    public static final String TYPE_VIEWED = "VIEWED";
    public static final String TYPE_CLICKED = "CLICKED";
    public static final String TYPE_CONVERTED = "CONVERTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "campaign_id", nullable = false)
    private Integer campaignId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    /** EMAIL | NONE — NONE honestly marks "no provider configured, recorded only". */
    @Column(name = "channel", length = 20)
    private String channel;

    @Column(name = "detail", length = 500)
    private String detail;

    /** Set only on CONVERTED rows (the bill that carried the campaign code). */
    @Column(name = "bill_id")
    private Integer billId;

    /** Set only on CONVERTED rows — the bill total at the time of the order. */
    @Column(name = "revenue")
    private Integer revenue;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at")
    private Date createdAt;
}
