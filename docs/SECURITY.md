# Security Review — Results & Recommendations

Review date: 2026-10-21 · Method: adversarial source audit + live probing of the running
system (all "verified" items were exercised against the live backend/frontend).

## Verified fixed / safe

| Area | Finding | Evidence |
|---|---|---|
| Authentication | bcrypt password storage; JWT (HS256, 10h) with role claim; login gated by account approval (`status`) | live probes; unapproved login → `Wait for admin approval.` |
| Authorization — categories/products | admin-only mutations enforced at service layer | non-admin → 401 `Unauthorized access` (live) |
| Authorization — Excel upload | admin-only (was open to any authenticated user) | staff token → 401 (live) |
| Authorization — bill delete | admin-only (was open) | staff token → 401 (live) |
| Authorization — payment records | list/by-id admin-only (was open — IDOR-class exposure) | staff token → 401 (live) |
| Authorization — frontend routes | role-aware nav + router guards mirror backend (defense in depth; backend remains authoritative) | unauthenticated browser → redirect to /login |
| JWT filter | thread-safe (ThreadLocal, cleared per request); malformed/expired tokens degrade to 403, never 500 | `Bearer garbage.token.here` → 403 |
| SQL injection | all persistence via Spring Data JPA / parameterized JPQL; no string-concatenated SQL in the codebase | source audit |
| XSS | React auto-escaping; zero `dangerouslySetInnerHTML` usages | `grep -r dangerouslySetInnerHTML` → 0 |
| CSRF | stateless JWT in Authorization header, no session cookies | config audit |
| Path traversal (bill PDF) | uuid strictly validated (`^[A-Za-z0-9-]{1,64}$`) before filesystem use | `../../etc/passwd` → 400 (live) |
| File upload | extension allowlist (.xls/.xlsx) + real POI parse gate + 5 MB size cap + 5000-row cap; POI zip-bomb ratios at library defaults | malformed/corrupt file → 400 structured error, no stack trace (live) |
| Card data | only masked PAN (last 4) persisted; CVV `@Transient` (never stored); full PAN never returned by any endpoint | DB check + response check (live) |
| Sensitive data in errors | exception internals never returned to clients; `e.getMessage()` leakage removed from upload path | corrupt-file response (live) |
| Secrets in repository | committed DB password removed; JWT secret externalized (dev default documented); mail sender externalized; NO payment gateway keys present (none exist in this codebase) | git diff of application.properties |
| Enumeration | forgot-password returns an identical generic response for known/unknown emails | live probe |
| Session expiry | any 401/403 on a protected call clears the session and redirects to login | browser E2E |

## Accepted risks / documented trade-offs

1. **JWT in localStorage** — XSS would expose the token. Mitigations in place: no
   dangerouslySetInnerHTML, React auto-escaping. A future hardening step is refresh-token
   rotation with httpOnly cookies; not part of the current backend contract.
2. **JWT default secret committed** — `JWT_SECRET` env var MUST be overridden in any real
   deployment (see DEPLOYMENT.md). The default exists only so local dev works out of the box.
3. **CORS** — payment controller allows all origins (`@CrossOrigin(origins="*")`, baseline
   behavior preserved) and the security config uses permissive defaults. Recommended: serve
   frontend and API from the same origin behind a reverse proxy (see DEPLOYMENT.md), which
   makes CORS irrelevant; or tighten the policy before exposing the API directly.
4. **Token expiry 10h, no revocation** — stateless JWTs cannot be server-revoked; logout is
   client-side. Shorter expiry + refresh tokens is the recommended evolution.
5. **Unauthenticated 403 for unknown paths** — intended (does not leak which paths exist).
6. **No rate limiting** — add at the reverse proxy (nginx `limit_req`) in production.

## Production checklist (see DEPLOYMENT.md for details)

- [ ] Override `JWT_SECRET`, `DB_USERNAME/DB_PASSWORD`, `MAIL_*`, `CAFE_UPI_PAYEE_VPA`
- [ ] Set `CAFE_BOOTSTRAP_ADMIN_*` once (then unset — bootstrap is a no-op when an admin exists)
- [ ] TLS termination at the reverse proxy; HSTS on
- [ ] Same-origin frontend+API (or tighten CORS)
- [ ] `scripts/sql/V20261021_01__payment_mapping_fix.sql` applied to legacy databases
- [ ] Backups scheduled and a restore REHEARSED (BACKUP_RESTORE.md)
