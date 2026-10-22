package com.inn.cafe.POJO;

import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * A restaurant marketing campaign / offer definition.
 *
 * ADDITIVE table. Lifecycle: DRAFT -> ACTIVE -> COMPLETED/ARCHIVED.
 *  - DRAFT: created by an admin or suggested by an automation rule; nothing sent.
 *  - ACTIVE: eligible for the customer banner and checkout attribution.
 *  - COMPLETED/ARCHIVED: no longer shown; history retained for attribution.
 *
 * Delivery is executed through the NotificationChannel adapter layer
 * (see Utils/NotificationChannel). When no channel is configured, sends are
 * recorded as campaign_events with channel=NONE and an honest detail —
 * no provider is faked, ever.
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "campaign")
public class Campaign implements Serializable {
    private static final long serialVersionUID = 78103L;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** DISCOUNT | WINBACK | VIP | ANNOUNCEMENT (free label, used for display). */
    @Column(name = "type", length = 30)
    private String type;

    /** ALL | NEW | RETURNING | FREQUENT | HIGH_VALUE | INACTIVE — resolved from REAL bill data at send time. */
    @Column(name = "segment", length = 30)
    private String segment;

    /** PERCENT | FLAT | NONE — descriptive offer type for the banner. */
    @Column(name = "offer_type", length = 20)
    private String offerType;

    /** Offer magnitude (e.g. 10 for 10%); null when offerType is NONE. */
    @Column(name = "offer_value")
    private Integer offerValue;

    /** Human-readable code the customer applies at checkout (server-verified). */
    @Column(name = "code", unique = true, length = 40)
    private String code;

    /** ISO yyyy-MM-dd window; blank start/end = open-ended on that side. */
    @Column(name = "starts_at", length = 10)
    private String startsAt;

    @Column(name = "ends_at", length = 10)
    private String endsAt;

    /** DRAFT | ACTIVE | COMPLETED | ARCHIVED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Optional cap on total conversions (null = unlimited). */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at")
    private Date createdAt;
}
