package com.inn.cafe.POJO;

import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * Audit row for every automation rule execution — the ACT and MEASURE steps
 * of the DETECT -> ANALYZE -> DECIDE -> ACT -> MEASURE loop.
 *
 * Every run records: the rule, how it was triggered (MANUAL by an admin or
 * SCHEDULED by the nightly job — disabled by default), what it detected, how
 * many actions it took, through which channel, the outcome status, and a
 * machine-readable detail document. Automation NEVER sends anything unless
 * an admin explicitly runs a send-capable rule AND a channel is configured;
 * suggestion-type rules only create DRAFT campaigns.
 *
 * dedupeKey makes idempotent rules safe to re-run (e.g. one thank-you per
 * bill uuid — the unique constraint turns a duplicate into a no-op).
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "automation_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_automation_dedupe", columnNames = "dedupe_key"))
public class AutomationRun implements Serializable {
    private static final long serialVersionUID = 78106L;

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** ORDER_THANK_YOU | WIN_BACK_DETECTION | VIP_RECOGNITION. */
    @Column(name = "rule", nullable = false, length = 40)
    private String rule;

    /** MANUAL | SCHEDULED. (column renamed from 'trigger' — reserved word in MySQL/MariaDB.) */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "detected_count")
    private Integer detectedCount;

    @Column(name = "action_count")
    private Integer actionCount;

    /** EMAIL | NONE. */
    @Column(name = "channel", length = 20)
    private String channel;

    /** JSON document: inputs, decisions, per-recipient outcomes. */
    @Lob
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** Idempotency key (e.g. "ORDER_THANK_YOU:BILL-123") — nullable for rules without dedupe. */
    @Column(name = "dedupe_key", length = 120)
    private String dedupeKey;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at")
    private Date createdAt;
}
