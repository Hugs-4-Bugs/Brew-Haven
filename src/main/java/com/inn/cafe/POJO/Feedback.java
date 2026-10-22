package com.inn.cafe.POJO;

import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * Post-order customer feedback (rating 1-5 + optional comment).
 *
 * ADDITIVE table. A feedback row is tied to one bill via its uuid and to the
 * submitting account via email; the service verifies the bill belongs to the
 * submitter, so customers can only rate their OWN orders. One feedback per
 * bill is enforced through the unique bill_uuid constraint (idempotent
 * resubmission updates the existing row instead of duplicating).
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_feedback_bill", columnNames = "bill_uuid"))
public class Feedback implements Serializable {
    private static final long serialVersionUID = 78102L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "bill_uuid", nullable = false, length = 128)
    private String billUuid;

    /** Email of the account that submitted the feedback. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** 1..5 stars, validated by the service. */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    /** Free-text comment (max 500 chars, enforced by the service). */
    @Column(name = "comment", length = 500)
    private String comment;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at")
    private Date createdAt;
}
