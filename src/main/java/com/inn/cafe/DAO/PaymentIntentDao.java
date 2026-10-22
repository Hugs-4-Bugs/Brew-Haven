package com.inn.cafe.DAO;

import com.inn.cafe.POJO.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.transaction.Transactional;
import java.util.Collection;

/** ADDITIVE DAO — see POJO/PaymentIntent. */
public interface PaymentIntentDao extends JpaRepository<PaymentIntent, Long> {

    PaymentIntent findByReference(String reference);

    PaymentIntent findByUserIdAndClientKey(String userId, String clientKey);

    PaymentIntent findByProviderOrderId(String providerOrderId);

    PaymentIntent findByBillId(Integer billId);

    /**
     * Atomic, database-level state-transition gate — the concurrency backstop
     * against double-confirm, webhook+confirm races and duplicate webhooks.
     * Returns 0 when another attempt already won the transition.
     */
    @Modifying
    @Transactional
    @Query("update PaymentIntent p set p.status = :to, p.updatedAt = CURRENT_TIMESTAMP " +
            "where p.reference = :reference and p.status in :from")
    int transitionStatus(@Param("reference") String reference,
                         @Param("to") String to,
                         @Param("from") Collection<String> from);

    /** Same gate, additionally stamping the provider payment id (success path). */
    @Modifying
    @Transactional
    @Query("update PaymentIntent p set p.status = :to, p.providerPaymentId = :providerPaymentId, p.updatedAt = CURRENT_TIMESTAMP " +
            "where p.reference = :reference and p.status in :from " +
            "and (p.providerPaymentId is null or p.providerPaymentId = :providerPaymentId)")
    int transitionStatusWithPayment(@Param("reference") String reference,
                                    @Param("providerPaymentId") String providerPaymentId,
                                    @Param("to") String to,
                                    @Param("from") Collection<String> from);
}
