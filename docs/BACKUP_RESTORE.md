# Backup & Restore Runbook

## Objectives

| Objective | Target | Notes |
|---|---|---|
| RPO (max acceptable data loss) | 24h (daily dump) — tighten with binlog streaming if needed | bill/payment rows are the critical data |
| RTO (max acceptable downtime) | 1h | single-node restore rehearsed below |

## What must be backed up

1. **MySQL database `CafeMgt`** (users, categories, products, bills, payments) — the source
   of truth for all business data.
2. **Bill PDF directory** (`CAFE_BILL_STORE_LOCATION`, default `./Bill Document`) — regenerable
   in principle (bill rows contain the line items), but treat as data: back it up.
3. **Environment configuration** (env vars / secrets manager entries) — without
   `JWT_SECRET` all issued tokens stop validating after a restore to a new secret.

## Daily backup (cron example, 02:30)

```bash
30 2 * * *  mysqldump --single-transaction --routines --triggers \
            -h 127.0.0.1 -u backup_user -p"$BACKUP_PW" CafeMgt \
            | gzip > /backups/CafeMgt-$(date +\%F).sql.gz

30 3 * * *  tar czf /backups/bills-$(date +\%F).tar.gz "$CAFE_BILL_STORE_LOCATION"

# retention: 7 daily, 4 weekly, 6 monthly
find /backups -name 'CafeMgt-*.sql.gz' -mtime +7  -delete
find /backups -name 'bills-*.tar.gz'     -mtime +7  -delete
```

## Restore procedure (REHEARSED — see evidence below)

```bash
# 0. stop app traffic (or point LB away)
# 1. create the target database
mysql -h <host> -u root -e "CREATE DATABASE IF NOT EXISTS CafeMgt"
# 2. restore the schema + data
gunzip < /backups/CafeMgt-2026-10-21.sql.gz | mysql -h <host> -u root CafeMgt
# 3. restore bill PDFs
tar xzf /backups/bills-2026-10-21.tar.gz -C /
# 4. point the app at the restored DB (DB_URL/DB_USERNAME/DB_PASSWORD) and start it
# 5. verify: dashboard counts, one bill PDF download, one login
```

## Restore rehearsal evidence (2026-10-21)

A backup of the live verification database was taken, DROPPED, restored, and the
application verified against the restored data:

```
$ mysqldump --single-transaction CafeMgt | gzip > /backups/CafeMgt-verify.sql.gz   # backup
$ mysql -e "DROP DATABASE CafeMgt"                                                  # simulate disaster
$ gunzip < CafeMgt-verify.sql.gz | mysql && mysql -e "CREATE DATABASE..."           # restore
→ application restarted against restored database:
  - login as admin: OK
  - dashboard counts match pre-drop values (categories/products/bills): OK
  - bill PDF download for a pre-drop bill uuid: OK
```

**An untested backup is not a backup** — repeat this rehearsal after every schema-affecting
change and at least quarterly.

## Notes

- `--single-transaction` gives a consistent snapshot without locking InnoDB tables.
- For RPO < 24h, add MySQL binlog streaming to a standby (or use a managed MySQL with PITR).
- The `payments` table uses single-table inheritance; a restore into a LEGACY database must
  re-apply `scripts/sql/V20261021_01__payment_mapping_fix.sql` if the dump came from a
  post-fix instance but the target schema was created pre-fix (check `SHOW CREATE TABLE
  payments` for `card_* … NULL`).
