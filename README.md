# Cafe Management System

Full-stack cafe management: POS billing with UPI/card/wallet/cash payments, self-contained
UPI QR (configured merchant payee), PDF receipts, product & category management, Excel bulk
import with structured per-row reporting, user administration with approval workflow, and a
dashboard with real analytics.

- **Backend**: Java 17 · Spring Boot 2.7 · Spring Security (JWT) · JPA/Hibernate · MySQL 8 · Apache POI · iText · ZXing
- **Frontend**: React 18 · TypeScript (strict) · Vite · React Router · GSAP (reduced-motion aware)

## Repository layout

```
├── src/                    # Spring Boot backend (com.inn.cafe: Rest/RestImpl/Service/ServiceImpl/
│                           #   DAO/POJO/Wrapper/JWT/Utils/Config/Constents — original structure preserved)
├── frontend/               # React + TypeScript SPA (Vite, dev port 3000)
├── ops/                    # Verified one-command stack scripts (start/stop/status, E2E, DB integrity)
├── scripts/sql/            # DB migrations + reference schema + demo seed
├── docs/                   # Audit, deployment, security, backup, limitations, test evidence,
│                           #   verification report + evidence, requirement document
├── ProductDetails*.xlsx    # Sample Excel import files
└── .github/workflows/      # Backend CI (JDK17 + MySQL service) & Frontend CI
```

## Quick start (local)

### 1. Prerequisites
- JDK 17+ (builds on 17 and 21)
- MySQL 8 (or MariaDB 10.6+)
- Node.js 20+ (frontend only)

### 2. Backend

```bash
# configure (defaults work for a local root-password-less MySQL; override via env in real envs)
export DB_URL='jdbc:mysql://localhost:3306/CafeMgt?createDatabaseIfNotExist=true'
export DB_USERNAME=root
export DB_PASSWORD=''
# first admin (created at startup when no admin exists; optional but recommended)
export CAFE_BOOTSTRAP_ADMIN_EMAIL=admin@cafe.local
export CAFE_BOOTSTRAP_ADMIN_PASSWORD='choose-a-strong-password'
# UPI QR merchant payee (required for QR generation; QR fails safe with 503 when unset)
export CAFE_UPI_PAYEE_VPA='yourmerchant@upi'

mvn spring-boot:run         # -> http://localhost:8000 (Swagger UI: /swagger-ui/index.html)
```

On a database created by the LEGACY version of this app, apply the one-time migration first:
```bash
mysql CafeMgt < scripts/sql/V20261021_01__payment_mapping_fix.sql
```

### 3. Frontend

```bash
cd frontend
npm install
npm run dev                 # -> http://localhost:3000 (proxies API to :8000; port in vite.config.ts)
```

Production build: `npm run build` → `frontend/dist` (serve statically; see docs/DEPLOYMENT.md).

### 4. First login
Sign-up creates `role=user, status=false` accounts that require admin approval. The
bootstrap admin (env vars above) is the only account that can log in on a fresh database.
Alternatively insert an admin manually with a bcrypt password hash.

## Test & verify

```bash
mvn verify                                  # backend: 45 unit tests + integration test
cd frontend && npm test && npm run build    # frontend: 28 tests + typecheck + production build
```

## API overview (authoritative contract)

Interactive: Swagger UI at `/swagger-ui/index.html`, OpenAPI JSON at `/v3/api-docs`.
Full inventory incl. request/response shapes: [docs/audit/ENDPOINT_INVENTORY.md](docs/audit/ENDPOINT_INVENTORY.md).

| Area | Endpoints |
|---|---|
| Auth | `POST /user/signup` `POST /user/login` `GET /user/checkToken` `POST /user/changePassword` `POST /user/forgotPassword` |
| Users (admin) | `GET /user/get` `POST /user/update` |
| Categories | `POST /category/add` `GET /category/get[?filterValue=true]` `POST /category/update` |
| Products | `POST /product/add` `GET /product/get` `POST /product/update` `POST /product/delete/{id}` `POST /product/updateStatus` `GET /product/getByCategory/{id}` `GET /product/getById/{id}` `GET /product/productList` `POST /product/uploadExcel` (admin) |
| Billing | `POST /bill/generateReport` `GET /bill/getBills` `GET+POST /bill/getPdf` `POST /bill/delete/{id}` (admin) |
| Dashboard | `GET /dashboard/details` |
| Payments | `POST /api/payment/process` `POST /api/payment/upi/verify` `POST /api/payment/upi/pay` `POST /api/payment/upi/qr-pay` `POST /api/payment/wallet/pay` `GET /api/payment/all` (admin) `GET /api/payment/{id}` (admin) `GET /api/payment/generate-qr/{upiId}/{amount}` |

## Documentation index

| Document | Contents |
|---|---|
| [docs/audit/DEFECT_REGISTER.md](docs/audit/DEFECT_REGISTER.md) | Every defect found (evidence-quoted), incl. Issues #2/#3/#4 |
| [docs/audit/REQUIREMENTS_MATRIX.md](docs/audit/REQUIREMENTS_MATRIX.md) | Requirements → status classification |
| [docs/audit/ENDPOINT_INVENTORY.md](docs/audit/ENDPOINT_INVENTORY.md) | Complete API contract + frontend consumer map |
| [docs/ISSUE_FIXES.md](docs/ISSUE_FIXES.md) | Issue #2/#3/#4 fix notes + verification evidence |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Env vars, production hardening, PostgreSQL strategy |
| [docs/SECURITY.md](docs/SECURITY.md) | Security review results & recommendations |
| [docs/BACKUP_RESTORE.md](docs/BACKUP_RESTORE.md) | RPO/RTO, backup & tested-restore procedure |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Honest limitations of the current architecture |
| [docs/TEST_EVIDENCE.md](docs/TEST_EVIDENCE.md) | Test results, E2E logs, final verification |
| [docs/verification/STARTUP_VERIFICATION_REPORT.md](docs/verification/STARTUP_VERIFICATION_REPORT.md) | Full-stack startup/connectivity verification, public-preview E2E |
| [docs/verification/evidence/](docs/verification/evidence/) | Screenshot evidence: E2E runs, repair proof, public-preview pass |
| [docs/requirements/](docs/requirements/) | Original production requirement document |
| [ops/](ops/) | Verified stack start/stop/status + E2E + DB-integrity scripts (env-driven) |

## Notes on this fork's fixes

This repository received a full-stack production-hardening pass (2026-10-21) — see
[docs/ISSUE_FIXES.md](docs/ISSUE_FIXES.md) for the complete, evidence-backed list:
Excel import ID-independence + structured reporting (Issue #2), UPI persistence/verification
incl. same-ID repeat payments (Issue #3), configured-merchant self-contained UPI QR (Issue #4),
plus 25+ collateral defect fixes discovered by source audit and live end-to-end testing.
