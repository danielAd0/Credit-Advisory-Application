# Admin API – consultants, products, employers, product quotations, client assignment

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
{ "fullName": "Naledi Mokoena", "email": "naledi@mafori.co.za", "temporaryPassword": "Welcome-Mafori-2026" }
```
`consultantCode` is **optional**: left out, the next code (`C0001`, `C0002`, ...) is generated. Send one only to keep
a code the person already has.

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
| GET | `/api/employers?search=&active=&employerType=&needsReview=&page=&size=` | – (sorted by name) |
| GET | `/api/employers/{id}` | – |
| POST | `/api/employers` | `{ name, company?, employerType? }` |
| PATCH | `/api/employers/{id}` | `{ name, company, employerType, version }` |
| PATCH | `/api/employers/{id}/active` | `{ active, version }` |

Names are trimmed and inner spaces collapsed; uniqueness is case-insensitive.
Errors: `409 employer:duplicate-name`, `404 employer:not-found`.

Every employer now has **`needsReview`**. It is `true` for an employer the weekly import created from a spelling
nobody had seen ("PHUMELELA LOCAL MUNIC"). An admin then either merges it into the right employer, or keeps it.

| Method | Path | Body | Who |
| --- | --- | --- | --- |
| GET | `/api/employers?needsReview=true` | – (the list to clean up) | any signed-in user |
| GET | `/api/employers/{id}/aliases` | – → `[ { id, alias, createdAt } ]`, the spellings matched to it | MANAGER, ADMIN |
| POST | `/api/employers/{id}/merge` | `{ targetEmployerId, version, reason }` | ADMIN |
| PATCH | `/api/employers/{id}/review` | `{ version }`: keep it as an employer of its own (`needsReview` → false) | ADMIN |

**Merge** moves employer `{id}`'s clients, sales and spellings to the target in one transaction. It then removes
employer `{id}` and keeps its name as one of the target's spellings, so later imports match it to the target.
Every sale still keeps the spelling it was imported with. `version` is employer `{id}`'s version.

`200` →
```json
{ "mergedEmployerId": 81, "mergedEmployerName": "PHUMELELA LOCAL MUNIC",
  "target": { "id": 12, "name": "PHUMELELA LOCAL MUNICIPALITY", "...": "...", "needsReview": false },
  "clientsMoved": 6, "transactionsMoved": 9, "aliasesMoved": 0, "nameKeptAsAlias": true }
```

| Status | type | When |
| --- | --- | --- |
| 422 | `employer:merge-into-itself` | the target is the same employer |
| 409 | `employer:merge-target-inactive` | the target is inactive (activate it first) |
| 409 | `conflict:stale-version` | someone changed employer `{id}` meanwhile |
| 404 | `employer:not-found` | either employer |
| 400 | | no `reason` (max 500) |

Audited as `EMPLOYER_MERGED` (with both names and the counts) and `EMPLOYER_REVIEWED`.

Frontend: an "Employers to review" list (`needsReview=true`), each with **Merge into…** (an employer picker
plus a reason) and **Keep**.

## 4. Product quotations (read: any signed-in user · change: ADMIN)

The **product quotation** on a client: the `C_BRANCHNAME` column of the old client spreadsheets. A list admins
manage, so consultants pick it from a dropdown and the same one is never spelled two ways. V20 creates the table
empty; admins fill it (or the launch load does, from the cleaned list).

| Method | Path | Body |
| --- | --- | --- |
| GET | `/api/product-quotations?search=&active=&page=&size=` | – (sorted by name; `size` default 100, max 200) |
| GET | `/api/product-quotations/{id}` | – |
| POST | `/api/product-quotations` | `{ name }` (max 100) |
| PATCH | `/api/product-quotations/{id}` | `{ name, version }` (rename) |
| PATCH | `/api/product-quotations/{id}/active` | `{ active, version }` |

```json
{ "id": 3, "name": "Sample Branch North", "active": true, "version": 0,
  "createdAt": "...", "updatedAt": "..." }
```

- Names are trimmed and inner spaces collapsed; uniqueness ignores capitals and spaces (the database enforces it too).
- Never deleted, because clients point at them. **Deactivate** instead: clients that have it keep it (shown with
  `"active": false` on the client), but it can no longer be chosen for a client.
- A rename shows on every client that has it.
- POST and both PATCHes are ADMIN only: a MANAGER or CONSULTANT gets `403`.

| Status | type | When |
| --- | --- | --- |
| 409 | `product-quotation:duplicate-name` | another one has that name, ignoring capitals and spaces |
| 409 | `conflict:stale-version` | someone else saved first |
| 404 | `product-quotation:not-found` | no such id |
| 400 | `validation:invalid-request` | blank name, or longer than 100 |

Audited as `PRODUCT_QUOTATION_CREATED` (`name`), `PRODUCT_QUOTATION_UPDATED` (`from`, `to`, only when the name really
changed), `PRODUCT_QUOTATION_ACTIVATED` / `PRODUCT_QUOTATION_DEACTIVATED` (`name`). Target type `PRODUCT_QUOTATION`.

Frontend: the **Product quotations** page (ADMIN and MANAGER; only ADMIN sees Add and Edit), and the dropdown on
the Add and Edit client forms (active ones only).

## 5. Assigning clients to consultants (MANAGER, ADMIN)

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
