# Admin API – consultants, products, employers, client assignment

Same conventions as `docs/api-auth-consultants-clients.md`: session cookie + `X-XSRF-TOKEN`,
errors are ProblemDetail JSON (`type` = `urn:credit-advisory:<code>`), lists return
`{ items, page, size, totalItems, totalPages }`, and every change that takes a `version`
returns `409 conflict:stale-version` if someone else saved first.

Every successful change below also writes a row to `audit_logs` (actor, action, target, safe metadata).

## 1. Consultant onboarding (ADMIN)

### POST /api/consultant-accounts

Creates the consultant record **and** its login (role `CONSULTANT`, linked to the consultant) in one
transaction. If anything fails, nothing is created.

```json
{ "consultantCode": "C010", "fullName": "Naledi Mokoena",
  "email": "naledi@mafori.co.za", "temporaryPassword": "Welcome-Mafori-2026" }
```

`201` → `{ "consultant": Consultant, "user": User }`

| Status | type | When |
| --- | --- | --- |
| 400 | `validation:invalid-request` | missing field, bad code format, password < 12 chars |
| 400 | `validation:weak-password` | password equals the email, or > 72 bytes |
| 409 | `consultant:duplicate-code` | code already used |
| 409 | `user:duplicate-email` | email already used |
| 403 | `auth:forbidden` | caller is not ADMIN |

Afterwards, use the existing endpoints:

- `POST /api/users/{id}/password-reset` `{ "temporaryPassword": "..." }` – new temporary password, clears lockout
- `PATCH /api/consultants/{id}/status` `{ "status": "SUSPENDED", "reason": "...", "version": 0 }` – signs the consultant out
- `PATCH /api/consultants/{id}` `{ "fullName": "...", "version": 0 }`

## 2. Products (read: any signed-in user · change: ADMIN)

Product = one column of the weekly register (Combo, IVC, Insurance, Mafori Card, …). The 15 products
from the workbook are seeded by V6. **Codes never change** (the import maps columns by code) and products
are **never deleted** – deactivate them instead.

| Method | Path | Body |
| --- | --- | --- |
| GET | `/api/products?search=&active=&page=&size=` | – (sorted by displayOrder, name) |
| GET | `/api/products/{id}` | – |
| POST | `/api/products` | `{ code, name, standardPrice?, countsTowardQuotation, countsTowardBanked, displayOrder? }` |
| PATCH | `/api/products/{id}` | `{ name, standardPrice, countsTowardQuotation, countsTowardBanked, displayOrder, version }` |
| PATCH | `/api/products/{id}/active` | `{ active, version }` |

`code` is normalised: `"funeral cover"` → `FUNERAL_COVER`. `standardPrice` may be `null` (negotiated).
Errors: `409 product:duplicate-code`, `404 product:not-found`.

Product object:

```json
{ "id": 4, "code": "MAFORI_CARD", "name": "Mafori Card", "standardPrice": 175.00,
  "countsTowardQuotation": true, "countsTowardBanked": true, "active": true,
  "displayOrder": 40, "version": 0, "createdAt": "...", "updatedAt": "..." }
```

## 3. Employers (read: any signed-in user · change: ADMIN)

Employers from the **Mafori Employer Schedule** (columns *Company*, *Employer*, *Employer Type*). V10 seeds the
63 distinct employers from the October 2026 schedule.

| Method | Path | Body |
| --- | --- | --- |
| GET | `/api/employers?search=&active=&employerType=&page=&size=` | – (sorted by name) |
| GET | `/api/employers/{id}` | – |
| POST | `/api/employers` | `{ name, company?, employerType? }` |
| PATCH | `/api/employers/{id}` | `{ name, company, employerType, version }` |
| PATCH | `/api/employers/{id}/active` | `{ active, version }` |

Names are trimmed and inner spaces collapsed; uniqueness is case-insensitive.
Errors: `409 employer:duplicate-name`, `404 employer:not-found`.

## 4. Assigning clients to consultants (MANAGER, ADMIN)

1. Find clients without a consultant: `GET /api/clients?unassigned=true`
2. Find active consultants: `GET /api/consultants?status=ACTIVE`
3. Assign:

### POST /api/client-assignments

```json
{ "consultantId": 3, "clientIds": [101, 102], "reason": "New client allocation" }
```

`200` → `{ "batchId": "uuid-or-null", "assignedCount": 2, "consultant": { "id": 3, "consultantCode": "C003", "fullName": "..." } }`

All or nothing: client rows are locked, and one history row per client is written
(`changeType: MANUAL`, `fromConsultant: null`). See it with `GET /api/clients/{id}/assignments`.

| Status | type | When |
| --- | --- | --- |
| 400 | `assignment:duplicate-clients` | a client id is listed twice |
| 404 | `consultant:not-found` / `assignment:client-not-found` | unknown ids |
| 409 | `assignment:already-assigned` | a client already has a consultant – use `POST /api/client-transfers` |
| 422 | `assignment:consultant-not-active` | consultant is INACTIVE or SUSPENDED (FR-17) |

Clients that **already** have a consultant are moved with the existing `POST /api/client-transfers`
(`fromConsultantId`, `toConsultantId`, `clientIds`, `reason`).
