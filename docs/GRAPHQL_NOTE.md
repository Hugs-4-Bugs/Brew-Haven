# GraphiQL 5 — Applicability Note (Honest Limitation Record)

## Finding

This repository's API is a **REST + OpenAPI (Swagger) API** implemented with Spring MVC
(`@RestController` interfaces under `com.inn.cafe.Rest` / `RestImpl`). There is **no GraphQL
schema, no GraphQL servlet, and no graph endpoint** anywhere in the backend, and none is
required by any existing frontend integration or CI job.

## Consequence

- **GraphiQL 5 (a GraphQL IDE) is NOT applicable** to this API architecture. No GraphQL
  endpoint exists for it to query.
- Installing or wiring GraphiQL here would fabricate compatibility that does not exist.
- Per the governing requirement ("do NOT fabricate compatibility; document the limitation"),
  this file is the durable record of that decision.

## Authoritative contract sources

1. **Runtime OpenAPI JSON**: `GET /v3/api-docs` (served by springdoc-openapi 1.7.0; verified
   200 unauthenticated in baseline probes).
2. **Swagger UI**: `GET /swagger-ui/index.html` (verified 200).
3. **Backend source of truth**: `src/main/java/com/inn/cafe/Rest/*` + `RestImpl/*` —
   catalogued in `docs/audit/ENDPOINT_INVENTORY.md`.

## Verification aid actually used

For live API verification in this engagement, HTTP probes were executed directly against the
running backend (curl + browser automation), which exercises the real contract — a strictly
stronger check than an IDE against a mocked schema.
