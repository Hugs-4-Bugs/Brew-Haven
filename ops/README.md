# ops/ — verified stack operations scripts

These are the exact scripts used during the final verification runs (2026-10-21),
published with credentials removed: **every secret is read from environment
variables** (see `env.example`). The live workspace copies are functionally
identical but additionally carry local dev defaults; these published copies
deliberately fail fast with a clear message when a credential is missing.

| Script | Purpose |
|---|---|
| `start-dev.sh` | One-command, idempotent full-stack start: MariaDB -> Spring Boot :8000 -> Vite :3000, with gated health checks (real login + proxy probe) before declaring success |
| `stop-dev.sh` | Clean shutdown in reverse order (graceful Spring shutdown, MariaDB flush) |
| `status-dev.sh` | Live status: processes, ports, end-to-end login probe, DB row counts |
| `e2e-verify.sh` | Full browser E2E (agent-browser): auth, CRUD, POS + payments (UPI/card/wallet/QR), bills/PDF, users, reload behavior |
| `e2e-repair.sh` | Surgical repair helpers for E2E-touched rows (row-scoped selectors) |
| `db-integrity-diff.py` | DB integrity diff vs a mysqldump backup (products/categories/users) |

## Stack layout assumption

The scripts manage a self-contained stack rooted at `CAFEMS_ROOT`
(default `/tmp/my-project`, override via env):

```
$CAFEMS_ROOT/
├── repo/              # clone of THIS repository (jar built into repo/target/)
├── tools/             # JDK 17, Maven, MariaDB runtimes (not redistributable here)
│   └── mariadb-data/  # MariaDB datadir (CafeMgt)
├── logs/              # service logs + run/ pids
└── artifacts/         # bill PDFs, E2E screenshots, backups
```

Adapt `TOOLS`/`REPO` paths if your layout differs. On a fresh machine you need:
JDK 17, Maven, a MariaDB 10.6+/MySQL 8 datadir with the `CafeMgt` database
(schema is auto-created by Hibernate `ddl-auto=update`, or use
`scripts/sql/schema-reference.sql` + `scripts/sql/seed-demo-data.sql`), and the
`DB_USERNAME` account granted on `CafeMgt.*`.

## Usage

```bash
cp ops/env.example .env && chmod 600 .env && vi .env   # fill real values
set -a; . ./.env; set +a
./ops/start-dev.sh     # starts everything; prints URLs when healthy
./ops/status-dev.sh
./ops/stop-dev.sh
```

Startup survival: services are daemonized with `setsid --fork` (re-parented to
PID 1) so they survive the invoking shell exiting.
