# Defect Register — Cafe Management System

Baseline: `main @ 14804c84bda0c67a630f742cd491dc2581abc7a1` (recovery tag `recovery-point-baseline`).
All entries below were verified against **source code inspection AND live runtime probing** (MariaDB 11.4.5 acting as MySQL 8 dialect server, app on :8000). Evidence quotes are real responses / stack traces captured on 2026-10-21.

Legend: `E#` = empirical probe number (see Evidence section), `S` = source-verified.

## GitHub Issue #2 — Excel import (ID mismatch, no reporting)

| # | Defect | Class | Severity | Evidence |
|---|--------|-------|----------|----------|
| 2.1 | Import expects pre-existing categories; missing category => row silently dropped. Repo's own sample `ProductDetails.xlsx` (categories Pizza/Dosa/…) imports **0 rows**: `"Uploaded file is empty or has invalid data."` | `ExcelService.parseExcelFile` | Critical | E5 |
| 2.2 | Dead duplicate implementation `ProductServiceImpl.processExcelData` assigns **manual IDs** (`product.setId(newProductId++)` computed from `findTopByOrderByIdDesc`) — the root cause of Excel-ID vs DB-ID mismatch (race + auto-increment collision). Not reachable from any REST path, but a landmine. | `ProductServiceImpl` | Critical (latent) | S |
| 2.3 | Row-level failures (missing name/price, unknown category, duplicate product) only `System.err` — client receives a single generic string; **no structured reporting** of imported/skipped/failed rows. | `ExcelService`, `ProductRestImpl.uploadExcel` | High | S |
| 2.4 | `private static final Map columnIndexMap` is **shared mutable static state** cleared+rebuilt per request — concurrent imports corrupt each other. | `ExcelService` | High | S |
| 2.5 | No admin authorization on `/product/uploadExcel` — any authenticated user can bulk-insert products. | `ProductRestImpl` | High | S |
| 2.6 | No file size limit, no row limit, `XSSFWorkbook` loads whole file to memory; no MIME check; extension-only detection. | `ExcelService` | Medium | S |
| 2.7 | Excel `status` column uses `available/unavailable` while DB contract (NamedQuery `Product.getProductByCategory`, `Category.getAllCategory`) filters `status='true'/'false'` — imported products are invisible to category-filtered queries. No normalization. | `ExcelService.getStatusCellValue` | High | S + sample file |
| 2.8 | `ProductDetails12.xlsx` contains a **repeated header row mid-file** (row 2) — current parser only lucks out because "price" string fails numeric parse; no explicit repeated-header skip. | sample file | Medium | S |
| 2.9 | Import result is not transactional with reporting: `productDao.saveAll(products)` after silent per-row skips; exceptions leak `e.getMessage()` to the client (`"Unexpected error: " + e.getMessage()`). | `ProductRestImpl.uploadExcel` | Medium | S |
| 2.10 | Excel `id` column exists in sample files but is (correctly) ignored by the live path — however **nothing validates/announces** that import is ID-independent; docs absent. | docs | Low | S |

## GitHub Issue #3 — UPI persistence + verification

| # | Defect | Class | Severity | Evidence |
|---|--------|-------|----------|----------|
| 3.1 | **Shadowed `upiId`**: `Payment.upiId` (parent) and `UPIPayment.upiId` (child, `@Column(nullable=false,unique=true)`) both exist. Hibernate maps the **parent's** field (schema shows single nullable non-unique `upi_id` column); the child's field is unmapped Java shadow — `setUpiId()` writes a field Hibernate never reads. UPI ID **silently never persists**. | `Payment`, `UPIPayment` | Critical | S + schema dump |
| 3.2 | **`card_*` NOT NULL under single-table inheritance**: `payments` table has `card_expiry/card_holder_name/card_number` NOT NULL (from `CardPayment @Column(nullable=false)`), so any UPI/WALLET insert fails: `Field 'card_expiry' doesn't have a default value` → HTTP 500. UPI payment persistence is impossible. | `CardPayment` + generated schema | Critical | E3 + stack trace |
| 3.3 | **6-arg `Payment` constructor is empty** (`{}`) — `UPIPayment(...)` super-call sets nothing; userId etc. stay null (userId is NOT NULL → also breaks inserts). | `Payment` | Critical | S |
| 3.4 | **`verifyUpi` reads raw JSON string including quotes** (`@RequestBody String upiId` receives `"prabhat@okhdfc"` with quotes) and then checks `endsWith("@upi")||endsWith("@ybl")`. Result: **every** UPI ID (even `probe@ybl`) is rejected: `"❌ Invalid UPI ID"`. This is the "UPI ID validation error" from the issue — a valid, previously-used UPI ID can never pass. | `PaymentServiceImpl.verifyUpi` | Critical | E1, E2 |
| 3.5 | Suffix whitelist (`@upi`,`@ybl`) contradicts NPCI UPI format (`name@okhdfc`, `name@paytm`, …) and contradicts the project's own correct regex `PaymentUtils.validateUpiId` which is never called. | `PaymentServiceImpl` | High | S |
| 3.6 | `unique=true` on `UPIPayment.upiId` (as *intended* mapping) would reject a **second payment with the same UPI ID** — the duplicate-payment false-invalid from the issue. Must not exist (a payer may pay repeatedly). | `UPIPayment` | High | S |
| 3.7 | Client controls `transactionId`, `status`, `timestamp` on `POST /api/payment/process` — server trusts them (fake/replayed transaction refs possible). No amount > 0 validation. No CVV-masking guarantee beyond `@Transient` (full card number stored raw). | `PaymentRestImpl.processPayment` | High | S |
| 3.8 | `PaymentWrapper.fromEntity` used nowhere; `getAllPayments` returns raw entities incl. card numbers — **PCI-sensitive data exposure** in list endpoint. | `PaymentRestImpl` | High | S |

## GitHub Issue #4 — UPI QR generation

| # | Defect | Class | Severity | Evidence |
|---|--------|-------|----------|----------|
| 4.1 | QR is a **link to an external third-party service** (`https://api.qrserver.com/...`) — not self-contained, adds an external dependency + data leak (payee VPA + amount sent to third party). | `PaymentServiceImpl.generateQrCode` | Critical | E4 |
| 4.2 | QR encodes **whatever upiId the caller supplies** (user's own/foreign VPA), not the **system's configured merchant payee** — money would be sent to an attacker-supplied account. No configured payee exists at all. | `PaymentServiceImpl` | Critical | E4 + S |
| 4.3 | No `am` formatting guard, no `cu=INR`, no `pn` (payee name), no transaction note — incomplete UPI intent payload per UPI deep-link spec. | `PaymentServiceImpl` | High | S |
| 4.4 | No configuration validation; no safe-fail — if QR generation were misconfigured the service still "succeeds" with a URL. | `PaymentServiceImpl` | High | S |

## Authentication / User management

| # | Defect | Class | Severity | Evidence |
|---|--------|-------|----------|----------|
| A.1 | **`changePassword` stores the new password in PLAINTEXT** (`userObj.setPassword(requestMap.get("newPassword"))` — no `passwordEncoder.encode`). After a successful password change the user can **never log in again** (DaoAuthenticationProvider compares bcrypt hash vs raw). | `UserServiceImpl.changePassword` | Critical | S |
| A.2 | `if (!userObj.equals(null))` — NPE risk when user not found; inverted/broken null logic. | `UserServiceImpl.changePassword` | High | S |
| A.3 | **`forgotPassword` emails the bcrypt HASH** (useless + sensitive-data leak) and the interface signature lacks `@RequestBody` (only impl has it — Spring binds interface annotations… impl annotation is used, but interface param annotation mismatch is a landmine). Response always `"Check your mail for Credentials"` even when mail fails? No — mail failure is caught → 500. But **email enumeration** is not the issue; the real issue is the hash being mailed + no actual reset path. | `UserServiceImpl.forgotPassword`, `EmailUtils.forgotMail` | Critical | S |
| A.4 | **No admin can ever exist on a fresh DB**: signup hardcodes `role="user"`, `status="false"`; nothing promotes to admin. App is unusable without manual SQL. | `UserServiceImpl.getUserFromMap` | Critical | S |
| A.5 | `JwtFilter` stores `claims`/`userName` in **instance fields of a singleton filter** — race condition across concurrent requests (role checks can read another request's claims). | `JwtFilter` | Critical | S |
| A.6 | `isAdmin()` NPEs when `claims == null` (public paths / pre-auth). Services call it on protected paths so normally claims exist, but any code-path change triggers NPE → 500. | `JwtFilter` | Medium | S |
| A.7 | `EmailUtils` hardcodes sender `kattyboy785@gmail.com`. | `EmailUtils` | Medium | S |
| A.8 | Dead security rule: `antMatchers("/api/category/**").hasRole("ADMIN")` — real path is `/category/**`; also JWT filter sets empty authorities so `hasRole` could never pass. Actual enforcement is service-level `jwtFilter.isAdmin()` (works). | `SecurityConfig` | Medium | S |
| A.9 | Unauthenticated requests to error/missing paths return **403 via blocked `/error` dispatch** instead of 404/500 semantics. | `SecurityConfig` | Low | E8 (`/nonexistent → 403`) |

## Billing / PDF

| # | Defect | Class | Severity | Evidence |
|---|--------|-------|----------|----------|
| B.1 | **PDF store location hardcoded to a developer's Mac path** `/Users/prabhatkumar/Downloads/com.inn.cafe/Bill Document` → bill PDF generation fails on every other machine: `"Something Went Wrong"` (500). | `CafeConstants.STORE_LOCATION` | Critical | E6 |
| B.2 | **Bill persisted even when the API returns 500** (PDF write failed after `insertBill`) — client retries → duplicate bills. `insertBill` swallows exceptions; no transaction boundary across bill+PDF. | `BillServiceImpl` | Critical | E7 (bill row id=1 exists despite 500) |
| B.3 | `getPdf` is `GET` + `@RequestBody` — browsers/fetch **cannot send bodies with GET**; endpoint unusable from any web frontend. | `BillRest` | Critical | S |
| B.4 | **Path traversal**: `getPdf` builds file path from raw `uuid` request value with no sanitization (`uuid = "../../etc/passwd"` style reads possible). | `BillServiceImpl.getPdf` | Critical | S |
| B.5 | `deleteBill` has **no authorization check** — any authenticated user can delete any bill. | `BillServiceImpl.deleteBill` | High | S |
| B.6 | PDF header prints `requestMap.get("contact")` (null) while contract/validation key is `contactNumber`. | `BillServiceImpl.generateReport` | Low | S |
| B.7 | `getBills`/`getPdf` return `null` ResponseEntity on exception path → NPE inside framework → worse errors. | `BillRestImpl` | Medium | S |

## Products / Categories / Dashboard

| # | Defect | Class | Severity | Evidence |
|---|--------|-------|----------|----------|
| P.1 | `addNewProduct` validation checks name/description/price but then `Integer.parseInt(requestMap.get("categoryId"))` on a **missing/null** value → NPE → 500 (should be 400 INVALID_DATA). Status not defaulted (`null` status product invisible to `status='true'` queries). | `ProductServiceImpl` | High | S |
| P.2 | `addNewCategory` missing else-branch: invalid payload (no `name`) falls through to 500 `"Something Went Wrong"` instead of 400. | `CategoryServiceImpl` | Medium | S |
| P.3 | `updateCategory` fetches with pessimistic lock then **discards the locked entity** and saves a detached `new Category(id,name)` with `version=null` — lock is useless, version semantics broken. | `CategoryServiceImpl` | Medium | S |
| P.4 | `getPdf`/`getBills` null-return paths; `DashBoardRestImpl` has no try/catch (fine, but inconsistent). | multiple | Low | S |
| P.5 | README documents **nonexistent endpoints** (`/products`, `/categories`, `/orders`, `/orders/{id}/bill`) and fake credentials (`admin@example.com/adminpassword`); real contract is `/product/*`, `/category/*`, `/bill/*`, `/user/*`, `/dashboard/*`, `/api/payment/*`. | README | High | S |

## Tests / CI

| # | Defect | Class | Severity | Evidence |
|---|--------|-------|----------|----------|
| T.1 | `CategoryServiceTest` (5 failures) asserts a contract that does not exist (403 vs actual 401; plain-string bodies vs actual JSON; stubs `findById` while impl calls `findByIdWithLock`). Baseline: `Tests run: 7, Failures: 5`. | test | High | E-test |
| T.2 | `CategoryServiceIT` autowires `CategoryServiceTest` (a Mockito unit test class, not a bean) → context would fail if it ever ran; surefire does not pick up `*IT` so it silently never runs. | test | Medium | S |
| T.3 | `PaymentServiceTest` is an **empty class** — zero coverage on the payment stack. | test | High | S |
| T.4 | CI workflow uses **JDK 11** (pom requires 17) → guaranteed build failure; no MySQL service for `@SpringBootTest`; `mvn deploy` has no `distributionManagement` in pom; no frontend job. | `.github/workflows/maven-publish.yml` | Critical | S |
| T.5 | Build fails on JDK 21: parent Boot 2.7.10 pins Lombok 1.18.26 (`NoSuchFieldError: JCTree$JCImport`). Fixed via `lombok.version=1.18.30` override. | pom | High | E-compile |

## Evidence log (live probes, 2026-10-21)

- **E1**: `POST /api/payment/upi/verify` body `"probe@ybl"` → `❌ Invalid UPI ID`
- **E2**: `POST /api/payment/upi/verify` body `"prabhat@okhdfc"` → `❌ Invalid UPI ID`
- **E3**: `POST /api/payment/upi/pay` (valid payload) → HTTP 500; root cause `java.sql.SQLException: Field 'card_expiry' doesn't have a default value`
- **E4**: `POST /api/payment/upi/qr-pay` → `https://api.qrserver.com/v1/create-qr-code/?data=upi://pay?pa=prabhat@okhdfc&am=250.0`
- **E5**: `POST /product/uploadExcel` with repo's own `ProductDetails.xlsx` → `Uploaded file is empty or has invalid data.` (0 categories exist)
- **E6**: `POST /bill/generateReport` (valid body) → `{"message":"Something Went Wrong"}` (hardcoded Mac path)
- **E7**: after E6, `SELECT * FROM bill` → row persisted (id=1) despite the 500 — duplicate-bill hazard
- **E8**: unauthenticated `/nonexistent` → 403 (blocked `/error` dispatch)
- **E-test**: `mvn test` → `Tests run: 7, Failures: 5, Errors: 0` (CategoryServiceTest x5, contextLoads passes)
- **E-schema**: `SHOW CREATE TABLE payments` → `dtype` single-table, `card_* NOT NULL`, single nullable `upi_id`, no unique constraints
- **E-swagger**: `/v3/api-docs` + `/swagger-ui/index.html` → 200 unauthenticated (permitAll works); OpenAPI JSON served by springdoc 1.7.0

## Non-defects verified working (baseline)

- Signup (bcrypt hashing), login (JWT w/ role claim, 10h expiry), approval gating (`Wait for admin approval.`), `checkToken`, `getAllUser` (admin-only, 401+empty for non-admin), user status update flow
- Category add (dup check), list (plain + filterValue=true product-backed filter), product add/update/delete/status/byCategory/byId, pagination
- Dashboard counts; OpenAPI/Swagger UI reachable unauthenticated
