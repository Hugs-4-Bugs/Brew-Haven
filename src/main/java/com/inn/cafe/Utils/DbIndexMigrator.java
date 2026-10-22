package com.inn.cafe.Utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Backward-compatible startup migrations that Hibernate's ddl-auto=update
 * cannot express (it does not add unique indexes to EXISTING tables).
 *
 * Migration 1 — COUPON 1:1 ENFORCEMENT (DB level):
 *   UNIQUE INDEX uk_campaign_event_campaign_bill ON campaign_event (campaign_id, bill_id)
 *   - one coupon application (a CONVERTED event) maps to exactly ONE bill;
 *   - duplicate/retried attribution for the same campaign+bill is rejected by
 *     the database, making idempotency physical, not just logical;
 *   - non-bill events (SENT/VIEWED/CLICKED) keep bill_id NULL and MariaDB
 *     allows multiple NULLs in a unique index — existing rows unaffected.
 */
@Slf4j
@Component
public class DbIndexMigrator implements ApplicationRunner {

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        applyCouponUniqueIndex();
    }

    private void applyCouponUniqueIndex() {
        String indexName = "uk_campaign_event_campaign_bill";
        try (Connection connection = dataSource.getConnection()) {
            boolean exists;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'campaign_event' "
                            + "AND INDEX_NAME = ?")) {
                ps.setString(1, indexName);
                ResultSet rs = ps.executeQuery();
                rs.next();
                exists = rs.getInt(1) > 0;
            }
            if (exists) {
                return;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE UNIQUE INDEX " + indexName
                        + " ON campaign_event (campaign_id, bill_id)");
            }
            log.info("Applied migration: unique index {} on campaign_event(campaign_id, bill_id) "
                    + "— coupon applications are now 1:1 with orders at the database level.", indexName);
        } catch (Exception e) {
            log.error("Migration failed ({}): {} — if duplicate (campaign_id, bill_id) rows exist, "
                    + "resolve them manually and restart.", indexName, e.getMessage());
        }
    }
}
