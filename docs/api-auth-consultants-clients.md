# API contract - auth, consultants, clients, transfers, users, dashboard, audit log, credit cases

Owner: Ntiyiso. Consumers: Jack (frontend). Status: DRAFT - sections 2 to 9 are implemented.

Any change to a request or response shape must be agreed with Jack before it is coded.

---

## 1. Conventions (apply to every endpoint)

### Base URL

- Local: `http://localhost:8080/api`

### Format

- Requests and responses are JSON (`Content-Type: application/json`), field names in `camelCase`.
- Timestamps are ISO-8601 in UTC, e.g. `"2026-10-28T08:15:00Z"`. The frontend converts to SA time for display.
- IDs are numbers (`12`), never strings.

### Authentication - session cookie

- Signing in creates a server session. The browser stores it in an `HttpOnly` cookie (`JSESSIONID`) that JavaScript cannot read.
- **Every** frontend request must send cookies: `fetch(url, { credentials: 'include' })`.
- Sessions expire after **30 minutes** of inactivity. The next request then returns `401`; the frontend redirects to the login page.
- A session also ends early (its next request returns `401`) when the user is disabled, their consultant becomes INACTIVE or SUSPENDED, or an admin resets their password or changes their roles or consultant link. The user signs in again and gets their up-to-date roles. Failed sign-ins, lockouts and unlocks do **not** end an open session.
- Any `401` from an endpoint other than login means "signed out": the frontend clears what it knows about the user and shows the login page.

### CSRF protection

- The backend sets a readable cookie `XSRF-TOKEN`.
- Every `POST`, `PATCH`, `PUT` and `DELETE` must copy that cookie's value into the header `X-XSRF-TOKEN`.
- On app start, the frontend calls `GET /api/auth/csrf` once so the cookie exists before the first form is submitted.
- Missing or wrong token: `403`.

### CORS (development only)

- The backend allows `http://localhost:5173` with credentials. Production serves frontend and API from the same origin, so no CORS is needed there.

### Errors - ProblemDetail (RFC 9457)

Every error response has `Content-Type: application/problem+json` and this shape:

```json
{
  "type": "urn:credit-advisory:<area>:<code>",
  "title": "Short human-readable summary",
  "status": 409,
  "detail": "Longer explanation that is safe to show the user",
  "instance": "/api/consultants/4"
}
```

Validation errors (`400`) add an `errors` array, one entry per bad field:

```json
{
  "type": "urn:credit-advisory:validation:invalid-request",
  "title": "Invalid request",
  "status": 400,
  "detail": "One or more fields are invalid",
  "errors": [{ "field": "fullName", "message": "must not be blank" }]
}
```

Error messages **never** contain a full ID number.

### Status codes used

| Code | Meaning                                                                                                 |
| ---- | ------------------------------------------------------------------------------------------------------- |
| 200  | OK                                                                                                      |
| 201  | Created - response has a `Location` header pointing at the new resource                                 |
| 204  | OK, no body                                                                                             |
| 400  | Validation failed                                                                                       |
| 401  | Not signed in / session expired / sign-in failed                                                        |
| 403  | Signed in, but not allowed (wrong role, not your client, bad CSRF token)                                |
| 404  | Not found - **also used when the record exists but you may not see it**, so its existence is not leaked |
| 409  | Conflict - duplicate (e.g. ID number, consultant code) or stale `version`                               |
| 422  | Request is valid but breaks a business rule (e.g. assigning to a SUSPENDED consultant)                  |
| 429  | Too many failed sign-ins from this network address - wait and try again (see `POST /api/auth/login`)    |

### Paging

List endpoints accept `?page=0&size=20` (page is 0-based, size max 100) and return:

```json
{
  "items": [ ... ],
  "page": 0,
  "size": 20,
  "totalItems": 134,
  "totalPages": 7
}
```

### Optimistic locking

- Every editable resource returns a `version` number.
- Every `PATCH` must send back the `version` it last read.
- If someone else saved in between, the response is `409` with type `urn:credit-advisory:conflict:stale-version`. The frontend should reload the record and ask the user to redo the change.

### ID numbers

- Lists and broad views show only the masked form: `"idNumberMasked": "*********5082"`.
- The full number (`"idNumber"`) appears only in a single-client response to an authorised user. Every such response is recorded in the audit log.
- **Full ID numbers are never put in URLs or query strings** (they would end up in server logs and browser history). Searching by ID number uses a POST body.

### Audit trail (FR-21)

Every successful change below writes one row to `audit_logs` in the **same transaction** as the change, so a refused or failed change leaves no entry. The actor is the signed-in user (or nobody, for sign-in events). Metadata never contains a password, a password hash or a full ID number - any 13-digit number in free text such as a reason is stored as `[redacted]`.

| Action | Target | Metadata |
|---|---|---|
| `CONSULTANT_CREATED` | consultant | `consultantCode`, `fullName` |
| `CONSULTANT_RENAMED` | consultant | `from`, `to` |
| `CONSULTANT_STATUS_CHANGED` | consultant | `from`, `to`, `reason`, `clientCount` (clients still assigned at that moment) |
| `USER_CREATED` | user | `email`, `roles`, `consultantId` |
| `USER_ROLES_CHANGED` | user | `from`, `to` (role lists) |
| `USER_CONSULTANT_LINK_CHANGED` | user | `fromConsultantId`, `toConsultantId` |
| `USER_ENABLED` / `USER_DISABLED` | user | - |
| `USER_PASSWORD_RESET` | user | - (never the password) |
| `USER_UNLOCKED` | user | - |
| `PASSWORD_CHANGED` | user (the user themselves) | - |
| `ACCOUNT_LOCKED` | user | `lockedUntil` (no actor: nobody is signed in) |
| `FIRST_ADMIN_CREATED` | user | `email` (no actor: created at start-up on a new server, see README "Running in production") |
| `SIGN_IN_RATE_LIMITED` | - | `address`, `failures`, `window` (never the emails tried) |
| `CLIENT_CREATED` | client | `consultantId` (or `null` if unassigned) |
| `CLIENT_VIEWED` | client | - (every time the full ID number is returned by `GET /api/clients/{id}`) |
| `CLIENT_LOOKUP` | client, or none if not found | `result`: `found`, `not-found`, or `not-visible` (exists, but the caller may not see it) |
| `CLIENT_UPDATED` | client | `fields`: the names of the fields that changed, never their values |
| `CLIENTS_TRANSFERRED` | destination consultant | `fromConsultantId`, `clientIds`, `count`, `batchId`, `reason` |
| `CLIENTS_ASSIGNED` | consultant | `clientIds`, `count`, `batchId`, `reason` (see `docs/api-admin.md`) |
| `CONSULTANT_ACCOUNT_CREATED` | consultant | `consultantCode`, `userId`, `email` (see `docs/api-admin.md`) |
| `CASE_CREATED` | case | `clientId` |
| `CASE_VIEWED` | case | - (every time `GET /api/cases/{id}` returns the case and its notes) |
| `CASE_UPDATED` | case | `fields`: the names of the fields that changed; `fromStatus`, `toStatus` when the status changed. Never the title |
| `CASE_NOTE_ADDED` | case | `noteId`. Never the note text |

Entries only record that something changed when it really changed (renaming to the same name, or setting the status it already has, writes nothing). Audit rows cannot be updated or deleted - the database refuses. Admins read them with `GET /api/audit-logs` (section 8).

---

## 2. Auth

### GET /api/auth/csrf

**Who:** anyone
**Purpose:** makes sure the `XSRF-TOKEN` cookie is set. Call once on app start.
**204 No Content**

### POST /api/auth/login

**Who:** anyone
**Request**

```json
{ "email": "thandi@mafori.co.za", "password": "the-password" }
```

**200 OK** - signed in, session cookie set. Body is the same as `GET /api/auth/me`.

**Errors**
| Status | When |
|---|---|
| 400 | email or password missing |
| 401 | sign-in failed - for **every** reason: wrong email, wrong password, account locked, user disabled, consultant INACTIVE or SUSPENDED |
| 429 | too many failed sign-ins from this network address (`urn:credit-advisory:auth:too-many-attempts`). The `Retry-After` header says how many seconds to wait. |

The `401` always has the same message: _"Sign-in failed. Check your details or try again later."_
Giving the same answer for every failure stops anyone from finding out which emails exist or which accounts are locked. Locked or suspended staff are told by their manager.

**Rules**

- Email is matched case-insensitively and trimmed.
- 5 failed attempts in a row lock the account for 15 minutes. A successful sign-in resets the count.
- Separately, 20 failed sign-ins from one network address within 5 minutes block sign-in **from that address** (any account, even with the right password) until the 5 minutes are up. This stops one address trying a common password against many accounts. Successful sign-ins never count, so staff sharing an office connection are not affected, and people who are already signed in are not affected either.
- On `429` the frontend should show: _"Too many failed sign-in attempts. Please wait a few minutes and try again."_
- A new session id is issued on sign-in (prevents session fixation).

### POST /api/auth/logout

**Who:** signed in
**204 No Content** - session ended, cookie cleared.

### GET /api/auth/me

**Who:** signed in
**200 OK**

```json
{
  "id": 12,
  "email": "thandi@mafori.co.za",
  "roles": ["CONSULTANT"],
  "consultant": {
    "id": 4,
    "consultantCode": "C004",
    "fullName": "Thandi Xundu"
  },
  "mustChangePassword": false
}
```

- `roles` contains one or more of `CONSULTANT`, `MANAGER`, `ADMIN`.
- `consultant` is `null` for users who are not consultants (e.g. an admin).
- `mustChangePassword` is `true` when an admin set the password (new account or reset). See "Password change required" below.
- The frontend uses `roles` only to decide which buttons and pages to show. The backend checks permissions again on every request.

**401** if not signed in.

### Password change required

When `mustChangePassword` is `true` (in the login response and `GET /api/auth/me`), the user may **only**:

- `GET /api/auth/me`, `GET /api/auth/csrf`
- `POST /api/auth/change-password`
- `POST /api/auth/logout`

Every other request returns `403` with type `urn:credit-advisory:auth:password-change-required`. The frontend should go straight to a "Choose your own password" screen after sign-in, and treat this `403` anywhere as "send them there". After a successful change the same session continues and `mustChangePassword` is `false`.

### POST /api/auth/change-password

**Who:** everyone signed in (changes their own password)

**Request**

```json
{
  "currentPassword": "Old-Password-123",
  "newPassword": "New-Password-456"
}
```

| Field             | Rules                                                                                                                      |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `currentPassword` | required                                                                                                                   |
| `newPassword`     | required; 12 characters minimum; 72 bytes maximum; not the same as the email address; different from the current password |

**204 No Content** - this session stays signed in (with a new session id) and `mustChangePassword` becomes `false`. The user's other sessions - other browsers or devices - are signed out on their next request.

**Errors**
| Status | When |
|---|---|
| 400 | invalid fields; `urn:credit-advisory:auth:wrong-current-password` when the current password is wrong; `urn:credit-advisory:validation:weak-password` when the new password breaks a rule (`detail` says which) |

Passwords are never logged or returned by any endpoint.

---

## 3. Consultants

### The Consultant object

```json
{
  "id": 4,
  "consultantCode": "C004",
  "fullName": "Thandi Xundu",
  "status": "ACTIVE",
  "statusChangedAt": "2026-10-01T07:30:00Z",
  "clientCount": 42,
  "version": 3,
  "createdAt": "2026-09-14T06:00:00Z",
  "updatedAt": "2026-10-01T07:30:00Z"
}
```

`status` is one of `ACTIVE`, `INACTIVE`, `SUSPENDED`.

`clientCount` is how many clients are currently assigned to the consultant (unassigned clients count for nobody). Read-only; it changes when clients are transferred. A new consultant starts at `0`.

### GET /api/consultants

**Who:** MANAGER, ADMIN
**Query parameters**
| Name | Example | Meaning |
|---|---|---|
| `search` | `thandi` | matches code or name, case-insensitive, partial |
| `status` | `ACTIVE` | filter by status; omit for all |
| `page`, `size` | `0`, `20` | paging |

Sorted by `fullName`.
**200 OK** - a page of Consultant objects.
**403** for CONSULTANT role.

### GET /api/consultants/{id}

**Who:** MANAGER, ADMIN
**200 OK** - one Consultant object. **404** if it does not exist.

### POST /api/consultants

**Who:** ADMIN
**Request**

```json
{ "consultantCode": "c004", "fullName": "Thandi Xundu" }
```

**201 Created** - Consultant object, `Location: /api/consultants/4`. New consultants are `ACTIVE`.

**Rules**

- `consultantCode` is trimmed and upper-cased before saving (`c004` becomes `C004`). Allowed: letters, digits, `_` and `-`, max 32, must start with a letter or digit.
- `fullName` required, max 200, not blank.

**Errors:** `400` invalid fields, `409` code already exists (`urn:credit-advisory:consultant:duplicate-code`).

### PATCH /api/consultants/{id}

**Who:** ADMIN
**Request**

```json
{ "fullName": "Thandi Mokoena", "version": 3 }
```

The consultant code cannot be changed (it is the stable key for Excel matching).
**200 OK** - updated Consultant object.
**Errors:** `400`, `404`, `409` stale version.

### PATCH /api/consultants/{id}/status

**Who:** ADMIN
**Request**

```json
{ "status": "SUSPENDED", "reason": "Under investigation", "version": 3 }
```

**200 OK** - updated Consultant object.

**Rules**

- `reason` required (max 500) - recorded in the audit log.
- Setting `INACTIVE` or `SUSPENDED` immediately ends the linked user's sessions (FR-18). Their next request returns `401`.
- Their clients stay assigned to them; managers find them with `GET /api/clients?consultantStatus=INACTIVE` (or `GET /api/clients?consultantId={id}`) and reassign them with `POST /api/client-transfers`. Nothing is redistributed automatically.
- The response's `clientCount` says how many clients still need to be handed over. If it is above `0` after setting `INACTIVE` or `SUSPENDED`, the frontend should offer "Reassign N clients" straight away.
- An INACTIVE or SUSPENDED consultant cannot receive new clients (FR-17).

**Errors:** `400` invalid status or missing reason, `404`, `409` stale version.

---

## 4. Clients

### The Client object - list item

Used by `GET /api/clients` and `POST /api/clients/lookup`. Never contains the full ID number.

```json
{
  "id": 101,
  "idNumberMasked": "*********4083",
  "firstName": "Lerato",
  "lastName": "Mokoena",
  "email": "lerato.mokoena@example.com",
  "phone": "+27821234567",
  "consultant": {
    "id": 4,
    "consultantCode": "C004",
    "fullName": "Thandi Xundu",
    "status": "ACTIVE"
  },
  "version": 2,
  "createdAt": "2026-10-20T09:12:00Z",
  "updatedAt": "2026-10-22T13:40:00Z"
}
```

### The Client object - single client

Used by `GET /api/clients/{id}`, `POST /api/clients` and `PATCH /api/clients/{id}`. Same as the list item, plus the full ID number.

```json
{
  "id": 101,
  "idNumber": "9001014800089",
  "idNumberMasked": "*********0089",
  "firstName": "Lerato",
  "lastName": "Mokoena",
  "email": "lerato.mokoena@example.com",
  "phone": "+27821234567",
  "consultant": {
    "id": 4,
    "consultantCode": "C004",
    "fullName": "Thandi Xundu",
    "status": "ACTIVE"
  },
  "version": 2,
  "createdAt": "2026-10-20T09:12:00Z",
  "updatedAt": "2026-10-22T13:40:00Z"
}
```

- `email` and `phone` may be `null`.
- `phone` is always returned in international format (`+27...`).
- `consultant` is `null` when the client is unassigned.
- The example numbers above are made up. Never use real client data in docs, tests or demos.

### Who can see which clients (applies to every client endpoint)

| Role       | Sees                                                           |
| ---------- | -------------------------------------------------------------- |
| CONSULTANT | only clients currently assigned to their own consultant record |
| MANAGER    | all clients                                                    |
| ADMIN      | all clients                                                    |

A client a CONSULTANT may not see is treated as if it does not exist: the response is `404`, never `403`, so the existence of the client is not revealed.

If department-level manager scope is confirmed later (SRS 4), MANAGER will be limited to their department.

### GET /api/clients

**Who:** everyone signed in (scoped - see table above)

**Query parameters**
| Name | Example | Meaning | CONSULTANT may use? |
|---|---|---|---|
| `search` | `moko` | first or last name, partial, case-insensitive | yes |
| `consultantId` | `4` | only clients of this consultant | no - ignored |
| `consultantStatus` | `INACTIVE` | only clients whose consultant has this status | no - ignored |
| `unassigned` | `true` | only clients with no consultant | no - ignored |
| `page`, `size` | `0`, `20` | paging | yes |

Sorted by `lastName`, then `firstName`.

**200 OK** - a page of Client **list items**.

**Rules**

- For a CONSULTANT, the filters marked "no" are ignored and results are always limited to their own clients, whatever parameters are sent.
- `unassigned=true` cannot be combined with `consultantId` or `consultantStatus`; doing so returns `400`.
- Searching by ID number is not supported here - use `POST /api/clients/lookup`.

**Errors:** `400` invalid parameters.

### GET /api/clients/{id}

**Who:** everyone signed in (scoped)

**200 OK** - the **single client** object, including the full `idNumber`.

**Rules**

- Every successful call writes an audit entry: action `CLIENT_VIEWED`, the actor's user id, target `CLIENT` / `<client id>`, and the time. The ID number is **never** written to the audit log.
- The frontend should only call this when the user opens a client's detail page - not to fill lists.

**Errors:** `404` not found, or not visible to the caller.

### POST /api/clients/lookup

**Who:** everyone signed in (scoped)

**Purpose:** find a client by full ID number without putting the number in the URL.

**Request**

```json
{ "idNumber": "9001014800089" }
```

Spaces and hyphens in `idNumber` are ignored.

**200 OK** - the Client **list item** (masked; open `GET /api/clients/{id}` to see the full number).

**Rules**

- Every lookup writes an audit entry: action `CLIENT_LOOKUP`, the actor, the result (`found` / `not-found` / `not-visible`) and the client id if one exists. The ID number searched for is **never** logged. The entry is written even when the answer is `404`.
- A CONSULTANT looking up someone else's client gets `404`, the same as if it did not exist.

**Errors**
| Status | When |
|---|---|
| 400 | not a valid SA ID number (`urn:credit-advisory:validation:invalid-id-number`): must be 13 digits, start with a real date, have citizenship digit 0/1/2 and pass the check digit. `detail` says which rule failed and never repeats the number. |
| 404 | no such client, or not visible to the caller |

### POST /api/clients

**Who:** everyone signed in

**Request**

```json
{
  "idNumber": "9001014800089",
  "firstName": "Lerato",
  "lastName": "Mokoena",
  "email": "lerato.mokoena@example.com",
  "phone": "082 123 4567",
  "consultantId": 4
}
```

| Field          | Required | Rules                                                                                       |
| -------------- | -------- | ------------------------------------------------------------------------------------------- |
| `idNumber`     | yes      | valid SA ID number (see lookup); spaces and hyphens ignored                                 |
| `firstName`    | yes      | trimmed, not blank, max 100                                                                 |
| `lastName`     | yes      | trimmed, not blank, max 100                                                                 |
| `email`        | no       | valid email, max 254                                                                        |
| `phone`        | no       | SA or international number, normalised to international format (same rules as the Excel import): `082 123 4567`, `0821234567` and `27821234567` all become `+27821234567`; `+` or `00` numbers are kept. Spaces, hyphens, dots and brackets are ignored. Anything else is `400` (`urn:credit-advisory:validation:invalid-phone`). |
| `consultantId` | no       | see assignment rules below                                                                  |

**201 Created** - the **single client** object, with `Location: /api/clients/101`.

**Assignment rules**
| Caller | `consultantId` | Result |
|---|---|---|
| CONSULTANT | omitted, or their own consultant id | assigned to themselves |
| CONSULTANT | someone else's id | `403` (`urn:credit-advisory:client:cannot-assign-to-others`) |
| CONSULTANT whose login has no consultant record | anything | `403` (`urn:credit-advisory:client:no-consultant-record`) |
| MANAGER / ADMIN | an ACTIVE consultant's id | assigned to that consultant |
| MANAGER / ADMIN | omitted | created unassigned |
| anyone | an INACTIVE or SUSPENDED consultant | `422` (`urn:credit-advisory:client:consultant-not-active`) |

**Other rules**

- When the new client is assigned, one assignment-history row is written: change type `MANUAL`, from `null` to the consultant, reason `"Client created"`.
- An audit entry `CLIENT_CREATED` is written (no ID number).
- The full ID number is stored encrypted, with a keyed hash for duplicate detection, exactly as the Excel import does - so a client created here and the same person in a later import are recognised as one client.

**Errors**
| Status | When |
|---|---|
| 400 | invalid fields (each listed in `errors`), invalid ID number, invalid phone |
| 403 | CONSULTANT tried to assign to someone else, or has no consultant record |
| 404 | `consultantId` does not exist |
| 409 | a client with this ID number already exists (`urn:credit-advisory:client:duplicate-id-number`) |
| 422 | the chosen consultant is not ACTIVE |

The `409` does **not** say which consultant has the existing client or show any of its details - the caller may not be allowed to know. A manager can find it with `POST /api/clients/lookup`.

### PATCH /api/clients/{id}

**Who:** everyone signed in (scoped)

**Request** - send **all four** editable fields as they should be afterwards (the edit form has them all), plus `version`:

```json
{ "firstName": "Lerato", "lastName": "Mokoena", "email": "lerato.m@example.com", "phone": null, "version": 2 }
```

| Field                   | Rules                                                         |
| ----------------------- | ------------------------------------------------------------- |
| `firstName`, `lastName` | required; same as create                                      |
| `email`, `phone`        | same as create; `null`, blank or left out clears the value    |
| `version`               | required                                                      |

**200 OK** - the updated **single client** object.

**Rules**

- The ID number cannot be changed here. Who may correct an ID number is still to be decided by the business (SRS 15).
- The consultant cannot be changed here - use `POST /api/client-transfers`.
- An audit entry `CLIENT_UPDATED` is written, listing which field names changed (not their values). Nothing changed, no entry.

**Errors:** `400` invalid fields, `404` not found or not visible, `409` stale version.

---

## 5. Transfers

Two endpoints change who a client belongs to; the "Reassign clients" screen uses both:

| Situation | Endpoint |
|---|---|
| Client has **no** consultant yet (`GET /api/clients?unassigned=true`) | `POST /api/client-assignments` - see `docs/api-admin.md` section 4 |
| Client already has a consultant and must move | `POST /api/client-transfers` - below |

Both are all-or-nothing, lock the client rows, write one history row per client and an audit entry.

### POST /api/client-transfers

**Who:** MANAGER, ADMIN

**Purpose:** move one or many clients from one consultant to another (FR-13, FR-14).

**Request**

```json
{
  "fromConsultantId": 4,
  "toConsultantId": 7,
  "clientIds": [101, 102, 103],
  "reason": "Thandi has left the company"
}
```

| Field              | Rules                                                          |
| ------------------ | -------------------------------------------------------------- |
| `fromConsultantId` | required; the consultant who currently has every listed client |
| `toConsultantId`   | required; must be ACTIVE; must differ from `fromConsultantId`  |
| `clientIds`        | required; 1 to 500 ids; no duplicates                          |
| `reason`           | required; trimmed, not blank, max 500                          |

**200 OK**

```json
{
  "batchId": "3f6c2a1e-8b7d-4c1a-9e2f-5d4b3a2c1e0f",
  "transferredCount": 3,
  "fromConsultant": {
    "id": 4,
    "consultantCode": "C004",
    "fullName": "Thandi Xundu"
  },
  "toConsultant": {
    "id": 7,
    "consultantCode": "C007",
    "fullName": "Sipho Ndlovu"
  }
}
```

**Rules**

- **All or nothing.** If any listed client no longer belongs to `fromConsultantId` (for example someone moved it a moment ago), nothing is transferred and the response is `409`. The frontend should reload the list and let the user try again.
- One assignment-history row is written per client, recording who made the change, when and why.
- One client: change type `TRANSFER`, `batchId` is `null`.
- Two or more clients: change type `BULK_TRANSFER`, and every row shares the same `batchId` so the whole handover can be traced as one action.
- Client cases, notes and history stay with the client and are never reset (FR-15).
- An audit entry `CLIENTS_TRANSFERRED` is written with the batch id, both consultant ids and the count.
- The source consultant may have any status (that is the point of a handover when someone leaves). Only the destination must be ACTIVE (FR-17).

**Errors**
| Status | When |
|---|---|
| 400 | invalid fields, same consultant on both sides, empty or too-long `clientIds`, duplicate ids |
| 404 | a consultant or a client does not exist |
| 409 | one or more clients do not currently belong to `fromConsultantId` (`urn:credit-advisory:transfer:ownership-changed`); `detail` lists the affected client ids |
| 422 | destination consultant is not ACTIVE (`urn:credit-advisory:transfer:destination-not-active`) |

### GET /api/clients/{id}/assignments

**Who:** MANAGER, ADMIN

**Purpose:** full assignment history for one client (FR-16).

**200 OK** - a plain list (not paged; a client has few assignments), newest first:

```json
[
  {
    "changedAt": "2026-10-27T10:05:00Z",
    "changeType": "BULK_TRANSFER",
    "fromConsultant": {
      "id": 4,
      "consultantCode": "C004",
      "fullName": "Thandi Xundu"
    },
    "toConsultant": {
      "id": 7,
      "consultantCode": "C007",
      "fullName": "Sipho Ndlovu"
    },
    "reason": "Thandi has left the company",
    "changedBy": { "id": 2, "email": "manager@mafori.co.za" },
    "batchId": "3f6c2a1e-8b7d-4c1a-9e2f-5d4b3a2c1e0f"
  },
  {
    "changedAt": "2026-10-20T09:12:00Z",
    "changeType": "IMPORT",
    "fromConsultant": null,
    "toConsultant": {
      "id": 4,
      "consultantCode": "C004",
      "fullName": "Thandi Xundu"
    },
    "reason": "Imported from workbook",
    "changedBy": { "id": 2, "email": "manager@mafori.co.za" },
    "batchId": null
  }
]
```

`changeType` is one of `IMPORT`, `MANUAL`, `TRANSFER`, `BULK_TRANSFER`, `UNASSIGN`.

**Errors:** `403` for CONSULTANT, `404` client does not exist.

---

## 6. Users (ADMIN only)

Every endpoint in this section returns `403` for CONSULTANT and MANAGER.

### The User object

```json
{
  "id": 2,
  "email": "thandi@mafori.co.za",
  "enabled": true,
  "roles": ["CONSULTANT"],
  "consultant": {
    "id": 4,
    "consultantCode": "C004",
    "fullName": "Thandi Xundu",
    "status": "ACTIVE"
  },
  "locked": false,
  "lockedUntil": null,
  "mustChangePassword": false,
  "lastLoginAt": "2026-10-29T08:12:00Z",
  "version": 3,
  "createdAt": "2026-10-20T09:00:00Z",
  "updatedAt": "2026-10-29T08:12:00Z"
}
```

| Field         | Meaning                                                            |
| ------------- | ------------------------------------------------------------------ |
| `roles`       | any of `CONSULTANT`, `MANAGER`, `ADMIN`, sorted in that order      |
| `consultant`  | the linked consultant record, or `null`                            |
| `locked`      | `true` while the account is locked after too many failed sign-ins  |
| `lockedUntil` | when the lock ends (only set while `locked` is `true`)             |
| `lastLoginAt` | last successful sign-in, or `null` if the user has never signed in |

The password (and its hash) is never returned.

### Rules that apply to every user change

- A user with the `CONSULTANT` role must be linked to a consultant record.
- Only an ACTIVE consultant can be linked, and each consultant can have at most one login.
- The last enabled ADMIN cannot be disabled or lose the `ADMIN` role, so there is always someone who can manage users.
- Emails are stored trimmed and lower-case, and must be unique.
- Changing a user's roles, consultant link or enabled flag, or resetting their password, signs that user out of every open session (their next request returns `401`). This includes an admin editing their **own** roles. Unlocking does not sign anyone out.

### GET /api/users

**Query parameters**
| Name | Example | Meaning |
|---|---|---|
| `search` | `thandi` | part of the email, case-insensitive |
| `page`, `size` | `0`, `20` | paging (max size 100) |

Sorted by `email`.

**200 OK** - a page of User objects.

### GET /api/users/{id}

**200 OK** - one User object.

**Errors:** `404` user does not exist.

### POST /api/users

**Request**

```json
{
  "email": "thandi@mafori.co.za",
  "temporaryPassword": "Temp-Password-2026",
  "roles": ["CONSULTANT"],
  "consultantId": 4
}
```

| Field               | Rules                                                           |
| ------------------- | --------------------------------------------------------------- |
| `email`             | required; valid email; max 254                                  |
| `temporaryPassword` | required; same rules as `newPassword` in change-password        |
| `roles`             | required; at least one                                          |
| `consultantId`      | required when `roles` contains `CONSULTANT`; otherwise optional |

The admin gives the temporary password to the user in person. The new user has `mustChangePassword: true` and must choose their own password before they can do anything else (section 2, "Password change required").

**201 Created** - the new User object, with a `Location` header.

**Errors**
| Status | When |
|---|---|
| 400 | invalid fields; `urn:credit-advisory:validation:weak-password` |
| 404 | `consultantId` does not exist (`urn:credit-advisory:consultant:not-found`) |
| 409 | email already used (`urn:credit-advisory:user:duplicate-email`); consultant already has a login (`urn:credit-advisory:user:consultant-already-linked`) |
| 422 | `CONSULTANT` role without a consultant (`urn:credit-advisory:user:consultant-role-needs-link`); consultant is not ACTIVE (`urn:credit-advisory:user:consultant-not-active`) |

### PATCH /api/users/{id}/roles

Replaces the user's roles with exactly the ones sent. The user is signed out and gets the new roles when they sign in again.

```json
{ "roles": ["MANAGER", "ADMIN"], "version": 3 }
```

**200 OK** - the updated User object.

**Errors**
| Status | When |
|---|---|
| 400 | invalid fields (empty `roles`, missing `version`) |
| 404 | user does not exist |
| 409 | stale `version`; removing `ADMIN` from the last enabled admin (`urn:credit-advisory:user:last-admin`) |
| 422 | adding `CONSULTANT` to a user with no linked consultant (`urn:credit-advisory:user:consultant-role-needs-link`) |

### PATCH /api/users/{id}/consultant

Links the user to a consultant, or unlinks them with `"consultantId": null`. The user is signed out.

```json
{ "consultantId": 4, "version": 3 }
```

**200 OK** - the updated User object.

**Errors**
| Status | When |
|---|---|
| 400 | missing `version` |
| 404 | user or consultant does not exist |
| 409 | stale `version`; consultant already has another login (`urn:credit-advisory:user:consultant-already-linked`) |
| 422 | consultant is not ACTIVE; unlinking a user who still has the `CONSULTANT` role (remove the role first) |

### PATCH /api/users/{id}/enabled

```json
{ "enabled": false, "version": 3 }
```

Disabling a user signs them out: their next request returns `401` (FR-18).

**200 OK** - the updated User object.

**Errors**
| Status | When |
|---|---|
| 400 | missing fields |
| 404 | user does not exist |
| 409 | stale `version`; disabling the last enabled admin (`urn:credit-advisory:user:last-admin`) |

### POST /api/users/{id}/password-reset

Sets a new temporary password and clears any lockout. Used when a user forgets their password, until password reset by email exists (FR-01). Every open session of that user is signed out, so a reset also locks out anyone who was using a stolen password. The user then has `mustChangePassword: true` again.

```json
{ "temporaryPassword": "Reset-Password-2026" }
```

No `version` is needed.

**200 OK** - the updated User object.

**Errors:** `400` invalid or weak password; `404` user does not exist.

### POST /api/users/{id}/unlock

No body. Clears the failed sign-in count and any lockout.

**200 OK** - the updated User object.

**Errors:** `404` user does not exist.

---

## 7. Dashboard

### GET /api/dashboard/summary

**Who:** everyone signed in (numbers are scoped by role)

**Purpose:** the counts on the dashboard (FR-20). Computed in the database, so they are correct however many clients there are. The frontend must use this instead of counting a page of `GET /api/clients`.

**200 OK** - MANAGER or ADMIN:

```json
{
  "scope": "ALL",
  "totalClients": 1250,
  "unassignedClients": 12,
  "clientsNeedingHandover": 42,
  "consultants": { "active": 18, "inactive": 3, "suspended": 1 }
}
```

**200 OK** - CONSULTANT:

```json
{
  "scope": "OWN",
  "totalClients": 87,
  "unassignedClients": null,
  "clientsNeedingHandover": null,
  "consultants": null
}
```

| Field | Meaning |
|---|---|
| `scope` | `ALL` (whole organisation) or `OWN` (the consultant's own clients) |
| `totalClients` | all clients, or for a CONSULTANT only their own (`0` if their login is not linked to a consultant) |
| `unassignedClients` | clients with no consultant. List them with `GET /api/clients?unassigned=true` |
| `clientsNeedingHandover` | clients still assigned to an INACTIVE or SUSPENDED consultant - the manager follow-up queue (SRS 7). List them with `GET /api/clients?consultantStatus=INACTIVE` and `...=SUSPENDED` |
| `consultants` | how many consultants have each status |

Counts only - no names or ID numbers.

**Errors:** `401` if not signed in.

---

## 8. Audit log (ADMIN only)

### GET /api/audit-logs

**Who:** ADMIN (`403` for everyone else)

**Purpose:** answer "who did what, when, and why" from the app (FR-21) - for example "who suspended Thandi?". Read-only.

**Query parameters** - all optional, combine freely:
| Name | Example | Meaning |
|---|---|---|
| `action` | `CONSULTANT_STATUS_CHANGED` | one action from the table in section 1 (case-insensitive) |
| `targetType` | `CONSULTANT` | `CONSULTANT` or `USER` (case-insensitive) |
| `targetId` | `4` | the id of that consultant or user - use together with `targetType` |
| `actorUserId` | `1` | only entries made by this user |
| `from` | `2026-11-01T00:00:00Z` | on or after this moment (ISO-8601, UTC) |
| `to` | `2026-11-02T00:00:00Z` | before this moment |
| `page`, `size` | `0`, `50` | paging (default size 50, max 100) |

Sorted newest first.

**200 OK** - a page of entries:

```json
{
  "items": [
    {
      "id": 812,
      "occurredAt": "2026-11-02T09:31:12.408Z",
      "action": "CONSULTANT_STATUS_CHANGED",
      "actor": { "id": 1, "email": "admin@mafori.co.za" },
      "targetType": "CONSULTANT",
      "targetId": "4",
      "metadata": { "from": "ACTIVE", "to": "INACTIVE", "reason": "Left the company", "clientCount": 42 }
    },
    {
      "id": 811,
      "occurredAt": "2026-11-02T09:02:40.115Z",
      "action": "ACCOUNT_LOCKED",
      "actor": null,
      "targetType": "USER",
      "targetId": "7",
      "metadata": { "lockedUntil": "2026-11-02T09:17:40.115Z" }
    }
  ],
  "page": 0, "size": 50, "totalItems": 2, "totalPages": 1
}
```

- `actor` is `null` for events where nobody was signed in (`ACCOUNT_LOCKED`, `SIGN_IN_RATE_LIMITED`).
- `metadata` is a JSON object whose keys depend on the action (section 1). The frontend should show the keys it knows and ignore the rest.
- Useful links from other screens: a consultant's history is `?targetType=CONSULTANT&targetId={id}`, a user's is `?targetType=USER&targetId={id}`.

**Errors:** `400` bad date format, or `from` not before `to` (`urn:credit-advisory:validation:invalid-filter`); `401` not signed in; `403` not ADMIN.

---

## 9. Credit cases

A credit case is a piece of advisory work for one client (FR-19), with notes added as the work goes on.

- A case belongs to a **client**, not to a consultant. Whoever the client is assigned to sees all of its cases, so a transfer keeps every case and note (FR-15).
- Notes are **append-only**: they cannot be edited or deleted. A correction is a new note. Every note keeps its original author, also after a transfer.
- Cases are **never deleted**. They are closed instead.

### Statuses

| Status        | Meaning                    |
| ------------- | -------------------------- |
| `OPEN`        | new, not started           |
| `IN_PROGRESS` | being worked on            |
| `CLOSED`      | finished; no new notes     |

This is a first set until the business defines the real stages (section 10). Values may be added later, never renamed, so the frontend should show an unknown status as plain text rather than fail.

### Who can see which cases (applies to every case endpoint)

The same rule as clients (section 4): a CONSULTANT sees the cases of the clients **currently** assigned to them; MANAGER and ADMIN see all cases. A case the caller may not see is answered `404`, never `403`.

### The Case object - list item

Used by `GET /api/cases`. No notes and never the full ID number.

```json
{
  "id": 31,
  "client": {
    "id": 101,
    "idNumberMasked": "*********0089",
    "firstName": "Lerato",
    "lastName": "Mokoena",
    "consultant": { "id": 4, "consultantCode": "C004", "fullName": "Thandi Xundu", "status": "ACTIVE" }
  },
  "title": "Debt review application",
  "status": "IN_PROGRESS",
  "openedAt": "2026-11-02T08:15:00Z",
  "closedAt": null,
  "createdBy": { "id": 7, "email": "thandi@example.com", "consultantName": "Thandi Xundu" },
  "version": 1,
  "createdAt": "2026-11-02T08:15:00Z",
  "updatedAt": "2026-11-02T10:02:00Z"
}
```

- `client.consultant` is who the client is assigned to **now** (`null` if unassigned) - not who opened the case.
- `createdBy.consultantName` is `null` for users without a consultant record (e.g. a manager).
- `closedAt` is set exactly while the status is `CLOSED`.

### The Case object - single case

Used by `GET /api/cases/{id}`, `POST /api/cases` and `PATCH /api/cases/{id}`. The list item plus all notes, **newest first**:

```json
{
  "id": 31,
  "client": { "...": "as in the list item" },
  "title": "Debt review application",
  "status": "IN_PROGRESS",
  "openedAt": "2026-11-02T08:15:00Z",
  "closedAt": null,
  "createdBy": { "id": 7, "email": "thandi@example.com", "consultantName": "Thandi Xundu" },
  "version": 1,
  "createdAt": "2026-11-02T08:15:00Z",
  "updatedAt": "2026-11-02T10:02:00Z",
  "notes": [
    {
      "id": 88,
      "note": "Payslip received.",
      "author": { "id": 7, "email": "thandi@example.com", "consultantName": "Thandi Xundu" },
      "createdAt": "2026-11-02T10:01:00Z"
    }
  ]
}
```

### GET /api/cases

**Who:** everyone signed in (scoped)

**Query parameters**
| Name | Example | Meaning | CONSULTANT may use? |
|---|---|---|---|
| `clientId` | `101` | only this client's cases (e.g. the cases tab on a client page) | yes |
| `consultantId` | `4` | only cases of clients currently assigned to this consultant | no - ignored |
| `status` | `OPEN` | only cases with this status | yes |
| `page`, `size` | `0`, `20` | paging | yes |

Sorted by `openedAt`, newest first.

**200 OK** - a page of Case **list items**. A CONSULTANT filtering by a client that is not theirs simply gets an empty page.

**Errors:** `400` invalid parameters (e.g. an unknown `status`).

### GET /api/cases/{id}

**Who:** everyone signed in (scoped)

**200 OK** - the **single case** object with all notes.

Every successful call writes an audit entry `CASE_VIEWED` (who and when - never the content).

**Errors:** `404` not found, or not visible to the caller.

### POST /api/cases

**Who:** everyone signed in, for a client they may see

**Request**

```json
{ "clientId": 101, "title": "Debt review application" }
```

| Field      | Rules                          |
| ---------- | ------------------------------ |
| `clientId` | required                       |
| `title`    | required, max 200, trimmed     |

**201 Created** - the new **single case** object (status `OPEN`, no notes), with a `Location: /api/cases/{id}` header.

**Errors:** `400` invalid fields, `404` (type `client:not-found`) client not found or not visible.

### PATCH /api/cases/{id}

**Who:** everyone signed in (scoped)

**Request** - send **both** editable fields as they should be afterwards, plus `version`:

```json
{ "title": "Debt review application", "status": "CLOSED", "version": 1 }
```

| Field     | Rules                                |
| --------- | ------------------------------------ |
| `title`   | required, max 200, trimmed           |
| `status`  | required, one of the statuses above  |
| `version` | required                             |

**200 OK** - the updated **single case** object.

**Rules**

- Any status can be changed to any other. Closing sets `closedAt`; reopening (`CLOSED` to `OPEN` or `IN_PROGRESS`) clears it.
- The client cannot be changed. To move a case to another consultant, transfer the client.
- An audit entry `CASE_UPDATED` is written with the names of the fields that changed and, for a status change, the old and new status. Nothing changed, no entry.

**Errors:** `400` invalid fields or unknown status, `404` not found or not visible, `409` stale version.

### POST /api/cases/{id}/notes

**Who:** everyone signed in (scoped)

**Request**

```json
{ "note": "Called the client, documents to follow." }
```

| Field  | Rules                        |
| ------ | ---------------------------- |
| `note` | required, max 4000, trimmed  |

**201 Created** - the new **Note** object (as in `notes` above). The author is the signed-in user.

**Rules**

- A `CLOSED` case takes no new notes: reopen it first.
- Notes cannot be edited or deleted - there is no endpoint, and the database refuses.
- An audit entry `CASE_NOTE_ADDED` is written with the note's id, never its text.

**Errors:** `400` invalid fields, `404` case not found or not visible, `409` (type `case:closed`) the case is closed.

---

## 10. Open questions

- **Correcting ID numbers:** can consultants correct a client's ID number, or only managers/admins? (SRS 15) - not editable for now.
- **Bulk transfer limit:** draft says 500 clients per transfer (SRS 2) - to confirm.
- **Session idle timeout:** draft says 30 minutes - to confirm.
- **Manager scope:** all clients for now; department-level scope if departments are confirmed (SRS 4).
- **Password reset (FR-01):** needs email sending (future). Until then an admin resets passwords.
- **Unassigning clients:** `UNASSIGN` exists in the history table, but there is no endpoint to unassign a client yet. Needed?
- **Case stages and fields (SRS 63):** cases have `OPEN` / `IN_PROGRESS` / `CLOSED` and a title for now. The business must define the real workflow stages, extra case fields, what a "credit record" holds, and any SLA targets.
- **Reopening cases:** anyone who can see a case may reopen it for now. Should only managers be allowed to?
