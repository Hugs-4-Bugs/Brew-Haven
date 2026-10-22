package com.inn.cafe.POJO;

import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * Server-side persistence of a customer's guest-experience preferences.
 *
 * ADDITIVE table (spring.jpa.hibernate.ddl-auto=update creates it on boot —
 * no existing table is touched). Anonymous visitors keep using the
 * versioned localStorage record implemented by the guest frontend; signed-in
 * customers additionally sync here so their preferences follow their account.
 *
 * The payload is the SAME versioned JSON document the frontend stores
 * locally (see frontend/src/guest/types.ts). No medical/health data beyond
 * the customer's own free-text note is ever requested, and the note is only
 * stored if the customer typed it themselves.
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "customer_preference",
        uniqueConstraints = @UniqueConstraint(name = "uk_customer_pref_email", columnNames = "email"))
public class CustomerPreference implements Serializable {
    private static final long serialVersionUID = 78101L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** Owner account email (lower-cased on write by the service). */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** Versioned preferences JSON document produced by the guest frontend. */
    @Lob
    @Column(name = "preferences", columnDefinition = "TEXT", nullable = false)
    private String preferences;

    /** Document version (mirrors the frontend schema version). */
    @Column(name = "version")
    private Integer version;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at")
    private Date updatedAt;
}
