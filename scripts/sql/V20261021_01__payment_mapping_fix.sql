-- =====================================================================
-- Migration: payment mapping fix (Issue #3)
-- Date: 2026-10-21
-- Applies to: databases created by the LEGACY entity mappings
--             (Spring Boot ddl-auto=update will NOT relax existing
--              NOT NULL constraints, so this must be applied manually
--              once per existing environment).
-- Safe for: MySQL 8.x / MariaDB 10.x+
-- Fresh databases created from the FIXED entities do NOT need this file.
-- =====================================================================

-- 1) Card columns must be NULLABLE: Payment/CardPayment/UPIPayment/WalletPayment share
--    the single `payments` table (single-table inheritance, `dtype` discriminator), so
--    UPI and WALLET rows legitimately have NULL card fields. The legacy NOT NULL
--    constraints made every UPI/WALLET payment insert fail with
--    "Field 'card_expiry' doesn't have a default value".
ALTER TABLE `payments`
    MODIFY `card_number` VARCHAR(255) NULL,
    MODIFY `card_holder_name` VARCHAR(255) NULL,
    MODIFY `card_expiry` VARCHAR(255) NULL;

-- 2) upi_id must NOT be unique: the same payer may legitimately pay multiple times
--    with the same UPI id (the unique constraint produced false "invalid UPI" errors
--    on repeat payments). No unique index is created by Hibernate after the fix, but
--    environments that manually added one should drop it:
-- ALTER TABLE `payments` DROP INDEX `upi_id`;   -- uncomment only if such an index exists

-- 3) Optional index for payment lookups by user (read performance on /api/payment/all)
CREATE INDEX `idx_payments_user_id` ON `payments` (`user_id`);

-- Post-migration verification:
--   SHOW CREATE TABLE payments\G
--   -> card_number/card_holder_name/card_expiry must show DEFAULT NULL
--   -> no UNIQUE KEY on upi_id
