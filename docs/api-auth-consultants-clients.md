# API contract - auth, consultants, clients, transfers, users

Owner: Ntiyiso. Consumers: Jack (frontend). Status: DRAFT - sections 2, 3, 5 and 6 and `GET /api/clients` are implemented. The other client endpoints in section 4 wait on the ID-number protection code.

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

The `401` always has the same message: _"Sign-in failed. Check your details or try again later."_
Giving the same answer for every failure stops anyone from finding out which emails exist or which accounts are locked. Locked or suspended staff are told by their manager.

**Rules**

- Email is matched case-insensitively and trimmed.
- 5 failed attempts in a row lock the account for 15 minutes. A successful sign-in resets the count.
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
  }
}
```

- `roles` contains one or more of `CONSULTANT`, `MANAGER`, `ADMIN`.
- `consultant` is `null` for users who are not consultants (e.g. an admin).
- The frontend uses `roles` only to decide which buttons and pages to show. The backend checks permissions again on every request.

**401** if not signed in.

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

**204 No Content** - the session stays signed in.

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
  "version": 3,
  "createdAt": "2026-09-14T06:00:00Z",
  "updatedAt": "2026-10-01T07:30:00Z"
}
```

`status` is one of `ACTIVE`, `INACTIVE`, `SUSPENDED`.

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
- Their clients stay assigned to them; managers find them with `GET /api/clients?consultantStatus=INACTIVE` and reassign manually. Nothing is redistributed automatically.
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
  "idNumber": "9001014800083",
  "idNumberMasked": "*********0083",
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
{ "idNumber": "9001014800083" }
```

Spaces and hyphens in `idNumber` are ignored.

**200 OK** - the Client **list item** (masked; open `GET /api/clients/{id}` to see the full number).

**Rules**

- Every lookup writes an audit entry: action `CLIENT_LOOKUP`, the actor, the result (`found` / `not-found`) and the client id if found. The ID number searched for is **never** logged.
- A CONSULTANT looking up someone else's client gets `404`, the same as if it did not exist.

**Errors**
| Status | When |
|---|---|
| 400 | not a valid SA ID number (must be 13 digits, start with a real date, have citizenship digit 0/1/2, pass the check digit) |
| 404 | no such client, or not visible to the caller |

### POST /api/clients

**Who:** everyone signed in

**Request**

```json
{
  "idNumber": "9001014800083",
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
| `phone`        | no       | SA or international number; normalised to `+27...` using the same rules as the Excel import |
| `consultantId` | no       | see assignment rules below                                                                  |

**201 Created** - the **single client** object, with `Location: /api/clients/101`.

**Assignment rules**
| Caller | `consultantId` | Result |
|---|---|---|
| CONSULTANT | omitted, or their own consultant id | assigned to themselves |
| CONSULTANT | someone else's id | `403` - consultants cannot assign clients to others |
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
| 400 | invalid fields (each listed in `errors`) |
| 403 | CONSULTANT tried to assign to someone else |
| 404 | `consultantId` does not exist |
| 409 | a client with this ID number already exists (`urn:credit-advisory:client:duplicate-id-number`) |
| 422 | the chosen consultant is not ACTIVE |

The `409` does **not** say which consultant has the existing client or show any of its details - the caller may not be allowed to know. A manager can find it with `POST /api/clients/lookup`.

### PATCH /api/clients/{id}

**Who:** everyone signed in (scoped)

**Request** - send only the fields being changed, plus `version`:

```json
{ "email": "lerato.m@example.com", "phone": null, "version": 2 }
```

| Field                   | Rules                                            |
| ----------------------- | ------------------------------------------------ |
| `firstName`, `lastName` | same as create; cannot be set to blank or `null` |
| `email`, `phone`        | same as create; `null` clears the value          |
| `version`               | required                                         |

**200 OK** - the updated **single client** object.

**Rules**

- The ID number cannot be changed here. Who may correct an ID number is still to be decided by the business (SRS 15).
- The consultant cannot be changed here - use `POST /api/client-transfers`.
- An audit entry `CLIENT_UPDATED` is written, listing which field names changed (not their values).

**Errors:** `400` invalid fields, `404` not found or not visible, `409` stale version.

---

## 5. Transfers

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

The admin gives the temporary password to the user in person; the user should then change it with `POST /api/auth/change-password`.

**201 Created** - the new User object, with a `Location` header.

**Errors**
| Status | When |
|---|---|
| 400 | invalid fields; `urn:credit-advisory:validation:weak-password` |
| 404 | `consultantId` does not exist (`urn:credit-advisory:consultant:not-found`) |
| 409 | email already used (`urn:credit-advisory:user:duplicate-email`); consultant already has a login (`urn:credit-advisory:user:consultant-already-linked`) |
| 422 | `CONSULTANT` role without a consultant (`urn:credit-advisory:user:consultant-role-needs-link`); consultant is not ACTIVE (`urn:credit-advisory:user:consultant-not-active`) |

### PATCH /api/users/{id}/roles

Replaces the user's roles with exactly the ones sent.

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

Links the user to a consultant, or unlinks them with `"consultantId": null`.

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

Sets a new temporary password and clears any lockout. Used when a user forgets their password, until password reset by email exists (FR-01).

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

## 7. Open questions

- **Correcting ID numbers:** can consultants correct a client's ID number, or only managers/admins? (SRS 15) - not editable for now.
- **Bulk transfer limit:** draft says 500 clients per transfer (SRS 2) - to confirm.
- **Session idle timeout:** draft says 30 minutes - to confirm.
- **Manager scope:** all clients for now; department-level scope if departments are confirmed (SRS 4).
- **Password reset (FR-01):** needs email sending (future). Until then an admin resets passwords.
- **Unassigning clients:** `UNASSIGN` exists in the history table, but there is no endpoint to unassign a client yet. Needed?
- **Forcing a password change:** users are not yet forced to change an admin-set temporary password on first sign-in. Needs a `must_change_password` column (Sifiso, migration V10) and a check in the frontend.
- **Sessions after a password reset:** a reset does not sign the user out of sessions that are already open. Should it?
