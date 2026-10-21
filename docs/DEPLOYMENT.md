# Deployment Guide

## Environment variables (backend)

All are optional locally (safe defaults) and REQUIRED in production.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/CafeMgt?createDatabaseIfNotExist=true` | JDBC URL (MySQL 8 / MariaDB compatible) |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / *(empty)* | database credentials |
| `JWT_SECRET` | *dev default (committed)* | HS256 signing key, **must be ≥ 32 bytes; override in production** |
| `CAFE_BOOTSTRAP_ADMIN_EMAIL` / `CAFE_BOOTSTRAP_ADMIN_PASSWORD` | *(empty)* | creates the first admin at startup when no admin exists (idempotent) |
| `CAFE_UPI_PAYEE_VPA` | *(empty)* | merchant UPI id encoded into every generated QR; **QR fails safe (503) when unset/invalid** |
| `CAFE_UPI_PAYEE_NAME` | `Cafe Management System` | payee name (`pn`) in the UPI intent |
| `CAFE_BILL_STORE_LOCATION` | `./Bill Document` | directory for generated PDF receipts (created on demand) |
| `CAFE_EXCEL_MAX_FILE_BYTES` / `CAFE_EXCEL_MAX_ROWS` | `5242880` / `5000` | Excel import caps |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | gmail defaults / placeholders | SMTP for forgot-password + admin notifications |

Frontend: `VITE_API_BASE` (default empty = same origin) — set only when API is hosted on a
different origin than the SPA.

## Running

```bash
# backend (jar)
mvn -DskipTests package spring-boot:repackage
DB_URL=… DB_USERNAME=… DB_PASSWORD=… JWT_SECRET=… CAFE_UPI_PAYEE_VPA=… \
  java -jar target/Cafeboookingsystem-0.0.1-SNAPSHOT.jar

# frontend
cd frontend && npm ci && npm run build
# serve frontend/dist with any static host / CDN (it is a fully static SPA)
```

## Reverse proxy (recommended topology)

Serve the SPA and API from ONE origin so browser CORS is irrelevant:

```
                ┌──────────────── nginx / load balancer (TLS) ────────────────┐
   https://cafe.example.com/  → static files from frontend/dist (SPA fallback)
   https://cafe.example.com/api|/user|/product|/category|/bill|/dashboard → :8000
                └────────────────────────────────────────────────────────────┘
```

nginx sketch:
```nginx
server {
  listen 443 ssl http2;
  ssl_certificate     /etc/letsencrypt/live/cafe.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/cafe.example.com/privkey.pem;
  add_header Strict-Transport-Security "max-age=31536000" always;

  root /var/www/cafe-frontend;         # frontend/dist
  location / { try_files $uri /index.html; }

  location ~ ^/(api|user|product|category|bill|dashboard)/ {
    proxy_pass http://127.0.0.1:8000;
    proxy_set_header Host $host;
    client_max_body_size 6m;           # matches multipart Excel limit
  }

  limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;   # rate limiting (optional)
}
```

## Horizontal scaling

The application is stateless (JWT auth, no HTTP sessions) and scales horizontally behind
the load balancer out of the box. Two pieces of LOCAL state must be addressed when running
more than one instance:

1. **Bill PDFs** (`CAFE_BILL_STORE_LOCATION`) — either point all instances at a shared volume,
   or (recommended) front the download flow with object storage in a future iteration.
2. **Graceful shutdown** is enabled (`server.shutdown=graceful`).

Database connection pooling defaults: HikariCP max 10 / min-idle 5 per instance — size your
DB connection budget accordingly.

## Database strategy

- **Current**: MySQL 8 (MariaDB 10.6+ compatible). Schema is managed by Hibernate
  `ddl-auto=update` — fine for development; for production, promote migrations explicitly
  (apply `scripts/sql/*.sql` through your DB change process).
- **PostgreSQL compatibility strategy** (deliberately NOT switched blindly):
  1. Take a logical backup (`mysqldump --single-transaction`).
  2. Spin up a staging PostgreSQL 14+ instance.
  3. Run the app against PostgreSQL with `spring.jpa.database-platform` set to the PostgreSQL
     dialect and `ddl-auto=validate` to check entity/schema agreement.
  4. The one vendor-specific item is `Bill.productDetails`
     (`columnDefinition = "json"` + hibernate-types `JsonType`): on PostgreSQL use
     `columnDefinition = "jsonb"` and the `hibernate-types-55` artifact family.
  5. Migrate data with a row-by-row ETL (the JSON column is the only non-trivial mapping),
     then run the FULL backend regression suite plus the E2E journey against staging.
  6. Cut over only after a restore rehearsal on PostgreSQL.

  Rationale: MySQL is what CI runs and what the data model was verified against; switching
  the primary database is a coordinated change, not a config flip.

## Legacy database migration

Databases created by the pre-fix build MUST apply once:
```bash
mysql CafeMgt < scripts/sql/V20261021_01__payment_mapping_fix.sql
```
(relaxes `card_*` NOT NULL for single-table payment inheritance; adds a `user_id` index;
documents the removal of any manual `upi_id` unique index).

## CI/CD

- `Backend CI & Package` (`.github/workflows/maven-publish.yml`): JDK 17 + MySQL 8 service →
  `mvn verify` (45 unit tests + integration test) → artifact upload; on GitHub release,
  publishes the jar to GitHub Packages.
- `Frontend CI` (`.github/workflows/frontend-ci.yml`): Node 20 → `npm ci` → strict typecheck →
  vitest (28 tests) → production build + bundle-size gate → `dist` artifact.
- Every release artifact is traceable to its exact Git commit SHA via the workflow run.
