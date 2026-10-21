# Requirements Matrix — Cafe Management System

> **FINAL STATUS (2026-10-21, end of production-hardening engagement):** every requirement
> row below reached **COMPLETE + VERIFIED** unless explicitly marked otherwise. Verification
> means: implementation exists, is wired to the real backend, tests pass, runtime behavior
> was exercised against the live system (see docs/TEST_EVIDENCE.md). Remaining PARTIAL/BLOCKED
> items are architectural facts documented in docs/KNOWN_LIMITATIONS.md — they are contract
> boundaries of the existing backend (e.g. no bill↔payment FK), not unfinished work.


Derived from: (A) source code, (B) backend endpoints, (C) entities, (D) services, (E) tests, (F) documentation, (G) UI requirements (production requirement doc), (H) prior checklist, (I) defect register, (J) runtime behavior, (L) DB schema, (M) CI config.
Status vocabulary: `COMPLETE+VERIFIED`, `IMPLEMENTED BUT BROKEN`, `PARTIALLY IMPLEMENTED`, `BACKEND ONLY`, `FRONTEND ONLY`, `API CONTRACT MISMATCH`, `TEST MISSING`, `SECURITY GAP`, `PRODUCTION GAP`, `DOCUMENTATION GAP`, `COMPLETELY MISSING`.

## Authentication & Users

| Requirement | Backend state (baseline) | Frontend state | Classification | Action |
|---|---|---|---|---|
| Signup | Works (bcrypt, dup-email 400) | none | BACKEND ONLY / TEST MISSING | build UI + tests |
| Login (JWT, role claim) | Works; token 10h | none | BACKEND ONLY | build UI |
| Unapproved-user gating | Works (`Wait for admin approval.`) | none | BACKEND ONLY | surface in UI |
| Token validation / protected endpoints | Works (403 for anonymous via Http403EntryPoint) | none | BACKEND ONLY | UI 401/403 handling |
| change password | **BROKEN: plaintext save → login impossible afterwards** (A.1) | none | IMPLEMENTED BUT BROKEN | fix + test |
| forgot password | **BROKEN: mails bcrypt hash; binding landmine** (A.3) | none | IMPLEMENTED BUT BROKEN | fix to temp-password flow + test |
| First-admin bootstrap | **MISSING — app unusable on fresh DB** (A.4) | none | COMPLETELY MISSING | config-gated bootstrap |
| User list (admin) | Works (401+[] for non-admin — odd but contract) | none | BACKEND ONLY | UI |
| Approve/disable user | Works + admin mail CC | none | BACKEND ONLY | UI (mail failure must not 500 — check) |
| Logout | N/A stateless JWT — client-side token discard | none | DOCUMENTATION GAP | document strategy |

## Categories

| Requirement | Backend | Frontend | Classification | Action |
|---|---|---|---|---|
| Create | Works; **invalid payload → 500** (P.2) | none | PARTIALLY IMPLEMENTED | fix 400 + test |
| List (all / product-backed filter) | Works | none | BACKEND ONLY | UI |
| Update | **Lock fetched then discarded; version=null merge** (P.3) | none | PARTIALLY IMPLEMENTED | fix + test |
| Duplicate prevention | exact-match only | — | PARTIALLY IMPLEMENTED | case-insensitive + test |
| Delete category | **No endpoint** (backend never had it) | — | N/A (not in contract) | document as out-of-contract |

## Products

| Requirement | Backend | Frontend | Classification | Action |
|---|---|---|---|---|
| Create | **NPE 500 on missing categoryId; status not defaulted** (P.1) | none | PARTIALLY IMPLEMENTED | fix + test |
| List (wrapper) | Works | none | BACKEND ONLY | UI |
| Update / delete / status | Works (admin-checked) | none | BACKEND ONLY | UI |
| By category / by id / pagination | Works | none | BACKEND ONLY | UI |
| Search/filter | **NO backend search endpoint** — client-side filtering of full list is the contract | none | API CONTRACT NOTE | implement client-side; document |

## Excel import (Issue #2)

| Requirement | Backend | Frontend | Classification | Action |
|---|---|---|---|---|
| Import xlsx/xls | **Repo's own sample imports 0 rows** (2.1) | none | IMPLEMENTED BUT BROKEN | fix |
| ID-independent import | dead path had manual IDs (2.2); live path ignores id column but undocumented | none | PARTIALLY IMPLEMENTED | enforce + test + doc |
| Row-level success/error reporting | **MISSING** (2.3) | none | COMPLETELY MISSING | implement + test |
| Thread safety | **static map race** (2.4) | none | SECURITY/PRODUCtion GAP | fix + concurrency note |
| Admin-only | **MISSING** (2.5) | none | SECURITY GAP | fix + test |
| File validation (ext/MIME/size) | partial (2.6) | none | PARTIALLY IMPLEMENTED | fix + test |
| Status normalization to DB contract | **MISSING** (2.7) | none | API CONTRACT MISMATCH | fix + test |
| Structured result UI | n/a | none | COMPLETELY MISSING | build UI |

## Billing / POS

| Requirement | Backend | Frontend | Classification | Action |
|---|---|---|---|---|
| Generate bill + PDF | **hardcoded Mac path → 500** (B.1); **bill persisted despite 500** (B.2) | none | IMPLEMENTED BUT BROKEN | fix + test |
| Bill list (admin all / user own) | Works | none | BACKEND ONLY | UI |
| PDF download | **GET+body unusable from browser** (B.3) + **path traversal** (B.4) | none | IMPLEMENTED BUT BROKEN + SECURITY GAP | POST variant + sanitize + test |
| Delete bill | Works but **no authz** (B.5) | none | SECURITY GAP | admin-check + test |
| Bill totals | server trusts client total (no recalc) — contract is client-computed; document | none | API CONTRACT NOTE | document honestly |

## Payments

| Requirement | Backend | Frontend | Classification | Action |
|---|---|---|---|---|
| UPI payment persistence | **impossible** (3.1/3.2/3.3) | none | IMPLEMENTED BUT BROKEN | fix mapping + migration SQL + test |
| UPI verification | **always invalid** (3.4/3.5) | none | IMPLEMENTED BUT BROKEN | fix regex + body handling + test |
| Same UPI ID reuse (duplicate payment) | blocked by intended unique constraint (3.6) | none | IMPLEMENTED BUT BROKEN | no unique constraint + test |
| Card payment | works via `/process` but raw card number stored, client controls status/txnId (3.7) | none | SECURITY GAP | server-side hardening + test |
| Wallet payment | returns redirect URL (deep-link style), no persistence of wallet flow | none | PARTIALLY IMPLEMENTED | document contract; keep |
| Payment list/by id | works but leaks card data (3.8) | none | SECURITY GAP | mask in response + test |
| UPI QR (Issue #4) | **external random QR, wrong payee** (4.1–4.4) | none | IMPLEMENTED BUT BROKEN + SECURITY GAP | configured payee + ZXing self-contained + config validation + test |

## Dashboard

| Requirement | Backend | Frontend | Classification | Action |
|---|---|---|---|---|
| Counts (category/product/bill) | Works | none | BACKEND ONLY | UI |
| Analytics (revenue, top items…) | **No backend analytics endpoints** — client computes from real bill/product data | none | COMPLETELY MISSING (frontend) | client-computed real analytics |

## Security / Production

| Area | Classification | Action |
|---|---|---|
| JWT secret hardcoded in source | SECURITY GAP | env override w/ default (documented) |
| DB password committed (`Prabhat72`) | SECURITY GAP | env externalization |
| Thread-unsafe JwtFilter (A.5) | SECURITY GAP | ThreadLocal fix + test |
| Blocked `/error` dispatch (A.9) | PRODUCTION GAP | permit `/error` |
| CORS default-allow-all methods | DOCUMENTATION GAP | document; tighten to config |
| No file upload limits (multipart) | PRODUCTION GAP | set max-file-size/max-request-size |
| No health endpoint | PRODUCTION GAP | actuator? → add lightweight /health via existing contract… (avoid new dep: document pom decision) — actually add spring-boot-starter-actuator (small, standard) |
| Logging: printStackTrace everywhere | PRODUCTION GAP | proper slf4j error logging, no secret logging |
| Backup/restore, PostgreSQL strategy, observability | DOCUMENTATION GAP | runbook docs |
| CI/CD | COMPLETELY BROKEN (JDK 11, no DB, no frontend) | rewrite |

## Frontend (complete scope)

All of: app shell/routing/protected routes/role nav/auth persistence/logout/session expiry, login/signup/forgot/change password, dashboard (real data + empty/error/loading states), products (CRUD/search/filter/status), categories (CRUD + nested products), user admin (approve/disable), billing/POS (builder → generate → payment → PDF), payments UI (UPI verify/pay/remembered ID/QR/wallet/card/duplicate protection), Excel import UI (progress/structured results/retry), responsive + a11y + reduced-motion, premium design system, GSAP motion, tests, production build.
Classification: **COMPLETELY MISSING → build (Vite + React + TS SPA in `frontend/`)**.

## Test matrix requirement

- Unit: CafeUtils/JwtUtil/PaymentUtils/user service (change/forgot)/payment service (verify/QR/process)/excel service/validation fixes — **TEST MISSING → write**
- Slice/IT: category IT (fix broken autowire), context boot — partially exists
- Frontend: vitest unit + build — **MISSING → write**
- E2E: 28 flows from production requirement — **MISSING → execute with live backend + browser**
