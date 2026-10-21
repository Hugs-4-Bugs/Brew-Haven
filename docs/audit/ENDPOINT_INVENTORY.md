# Endpoint Inventory (authoritative — verified from source + live probes)

Base URL: `http://<host>:8000`. Auth = `Authorization: Bearer <JWT>` unless noted.
Bodies marked `Map` are free-form JSON maps as implemented. Response column shows the ACTUAL contract (verified).

## User `/user`

| Method | Path | Auth | Controller guard | Request body | Response (status) | Frontend consumer |
|---|---|---|---|---|---|---|
| POST | `/user/signup` | public (filter+security) | — | `{name, contactNumber, email, password}` | `{"message":"Registration Completed"}` (200); `Email Already Registered` (400); `Invalid Data` (400) | Signup page |
| POST | `/user/login` | public | — | `{email, password}` | `{"token":"<jwt>"}` (200); `{"message":"Wait for admin approval."}` (400); `{"message":"Bad Credential."}` (400) | Login page |
| GET | `/user/get` | required | service: `jwtFilter.isAdmin()` | — | `[UserWrapper{id,name,contactNumber,email,status}]` (200); `[]` + 401 non-admin | User admin page |
| POST | `/user/update` | required | service: admin | `{id, status}` | `{"message":"User Status Updated Successfully"}` (200); `User id doesn't exists` (200); 401 unauthorized | User admin page |
| GET | `/user/checkToken` | required | security chain | — | `{"message":"true"}` (200) / 403 | session validator |
| POST | `/user/changePassword` | required | — (any user) | `{oldPassword, newPassword}` | `Password Updated Successfully` (200) / `Incorrect Old Password` (400) — **BUG A.1: saves plaintext** | settings page |
| POST | `/user/forgotPassword` | public | — | `{email}` | `Check your mail for Credentials` (200) — **BUG A.3: mails bcrypt hash** | forgot-password page |

## Category `/category`

| Method | Path | Auth | Guard | Request | Response | Frontend |
|---|---|---|---|---|---|---|
| POST | `/category/add` | required | admin (service) | `{name}` | `Category Added Successfully` plain (200) / `{"message":"Item Already Exists"}` (400) / `Item cannot be empty` (400) / 401 | categories page |
| GET | `/category/get` | required | — | `?filterValue=true` (optional) | `[Category{id,name,version,products?}]` (200); filterValue=true → only categories having ≥1 product with status 'true' | categories page, POS |
| POST | `/category/update` | required | admin (service) | `{id, name}` | `{"message":"Category Updated Successfully"}` (200) / `Category id doesn't exist` (400) / 401 | categories page |

## Product `/product`

| Method | Path | Auth | Guard | Request | Response | Frontend |
|---|---|---|---|---|---|---|
| POST | `/product/add` | required | admin | `{name, description, price, categoryId, status?}` | `Product Added Successfully.` (200) / `Invalid Data` (400) / 401 — **BUG P.1 NPE on missing categoryId** | product form |
| GET | `/product/get` | required | — | — | `[ProductWrapper{id,name,description,price,status,categoryId,categoryName}]` (200) | products page, POS |
| POST | `/product/update` | required | admin | `{id, name, description, price, categoryId, status}` | `Product Updated Successfully` (200) / `Product id doesn't exists` (200) / 400 / 401 | product form |
| POST | `/product/delete/{id}` | required | admin | path id | `Product Deleted Successfully.` (200) / `Product id does not exist.` (200) / 401 | products page |
| POST | `/product/updateStatus` | required | admin | `{id, status}` | `Product Status Updated Successfully` (200) / 400 / 401 | products page |
| GET | `/product/getByCategory/{id}` | required | — | path id | `[ProductWrapper{id,name}]` (200) | POS |
| GET | `/product/getById/{id}` | required | — | path id | `ProductWrapper{id,name,description,price}` (200) | product detail |
| POST | `/product/uploadExcel` | required | **none — BUG 2.5** | multipart `file` | text string — **BUG: no structured report** | Excel import page |
| GET | `/product/productList` | required | — | `?page&size` | `Page<Product>` JSON | (optional) products page |

## Bill `/bill`

| Method | Path | Auth | Guard | Request | Response | Frontend |
|---|---|---|---|---|---|---|
| POST | `/bill/generateReport` | required | — | `{name, contactNumber, email, paymentMethod, productDetails(JSON string), totalAmount, isGenerate?, uuid?}` | `{"uuid":" BILL-<ts>"}` (200) — **BUGS B.1/B.2/B.6** | POS checkout |
| GET | `/bill/getBills` | required | admin→all, user→own | — | `[Bill]` (200) | bills page |
| GET | `/bill/getPdf` | required | — | **body** `{uuid, ...}` | PDF bytes (200) — **BUGS B.3/B.4: GET+body, path traversal** | bill PDF download |
| POST | `/bill/delete/{id}` | required | **none — BUG B.5** | path id | `Bill Deleted Successfully` (200) / `Bill id does not exists` (200) | bills page (admin) |

**Planned additive fix**: `POST /bill/getPdf` (browser-usable variant; GET kept for curl/legacy compatibility).

## Dashboard `/dashboard`

| Method | Path | Auth | Response | Frontend |
|---|---|---|---|---|
| GET | `/dashboard/details` | required | `{"category":N,"product":N,"bill":N}` (200) | dashboard cards |

## Payment `/api/payment`

| Method | Path | Auth | Request | Response | Frontend |
|---|---|---|---|---|---|
| POST | `/api/payment/process` | required | `Payment` JSON (type discriminator via Jackson; fields: userId, paymentType, provider, transactionId, amount, status, upiId?, walletType?, cardNumber?, cardHolderName?, cardExpiry?, cvv?) | saved `Payment` (200) — **BUG 3.2 card NOT NULL for UPI/WALLET; BUG 3.7 client-controlled fields** | card payment |
| POST | `/api/payment/upi/verify` | required | raw JSON string `"upi@bank"` | `✅ UPI ID is valid` (200) / `❌ Invalid UPI ID` (400) — **BUG 3.4: quotes never stripped; always invalid** | UPI verify step |
| POST | `/api/payment/upi/pay` | required | `UPIPayment` JSON | `✅ UPI Payment stored successfully! Transaction ID: <id>` (200) — **BUGS 3.1–3.3: 500 always** | UPI pay step |
| POST | `/api/payment/upi/qr-pay` | required | `Payment` JSON `{upiId, amount}` | QR url/data-uri string (200) — **BUG 4.x: external random QR** | QR modal |
| POST | `/api/payment/wallet/pay` | required | `Payment` JSON `{walletType, amount}` | redirect URL string (200) | wallet step |
| GET | `/api/payment/all` | required | — | `[Payment]` — **BUG 3.8: leaks card numbers** | (admin) payments list |
| GET | `/api/payment/{id}` | required | path id | `Payment` (200) / 404 | payment detail |
| GET | `/api/payment/generate-qr/{upiId}/{amount}` | required | path | QR url/data-uri string — **BUG 4.x** | QR display |

## Docs / infra

| Path | Auth | Notes |
|---|---|---|
| `/v3/api-docs` | public (permitAll) | OpenAPI JSON — verified 200 |
| `/swagger-ui/index.html` | public (permitAll) | Swagger UI — verified 200 |
| `/error` | **blocked for anonymous (BUG A.9 → 403 instead of proper status)** | to permit |

## Notes

- No GraphQL anywhere; REST + OpenAPI is the authoritative contract (GraphiQL 5 not applicable — documented in docs/GRAPHQL_NOTE.md).
- Search/filter for products has no dedicated backend endpoint — contract is "fetch list, filter client-side".
- Bill totals and payment amounts are client-computed by contract; backend persists what it is given (documented limitation, no server recalculation endpoint exists).
- Payments are not FK-linked to bills in the backend data model (documented limitation).
