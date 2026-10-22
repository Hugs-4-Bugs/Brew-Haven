package com.inn.cafe.POJO;

import lombok.Data;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * Product-interaction events from the guest experience (authed sessions).
 * These are CLIENT-REPORTED measurement signals — every analytics surface
 * that consumes them labels them as such and never presents them as
 * server-verified facts.
 *
 * Tracked event types:
 *   UPSELL_SHOWN / UPSELL_ACCEPTED — pairing suggestion surfaced / accepted
 *   PREF_SAVED                     — preferences saved (funnel signal)
 *   CART_CREATED                   — first item added to the cart
 *   CHECKOUT_STARTED               — checkout drawer opened
 *   ORDER_COMPLETED                — bill generated (server-corroborated by the bill row)
 *
 * A simple in-memory rate limit per account protects the endpoint from spam
 * (see InteractionEventServiceImpl).
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "interaction_event",
        indexes = {
                @Index(name = "idx_int_event_type", columnList = "event_type"),
                @Index(name = "idx_int_event_product", columnList = "product_id")
        })
public class InteractionEvent implements Serializable {
    private static final long serialVersionUID = 78105L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "product_id")
    private Integer productId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "detail", length = 300)
    private String detail;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at")
    private Date createdAt;
}
