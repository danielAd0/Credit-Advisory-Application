# API contract - weekly register import

Owner: Sifiso (import). Written up by Ntiyiso from the code on `main` (commit `8e12fe8`). Consumers: Jack (frontend).
Status: DRAFT - implemented in `imports/weekly`. Sifiso: please check it matches what you intended.

The general rules of `docs/api-auth-consultants-clients.md` section 1 apply: session cookie, CSRF header on every
`POST`, ProblemDetail errors (`urn:credit-advisory:<code>`), paging shape, and **full ID numbers never appear** -
rows only ever show `idNumberMasked`.

---

## 1. Who can use it

**MANAGER and ADMIN only**, for every endpoint below. A CONSULTANT gets `403`.
Replacing a week that was already imported is **ADMIN only** (section 7).

## 2. The flow

```
1. GET  /api/imports/weekly-register/week-guess?fileName=...   (optional: pre-fill the week)
2. POST /api/imports/weekly-register/preview                    upload -> import job in state PREVIEWED
3. GET  /api/imports/{id}                                       summary: counts, days, blockers
   GET  /api/imports/{id}/rows      GET /api/imports/{id}/issues  (details, paged)
4. POST /api/imports/{id}/consultant-mappings                   once per unknown consultant name
5. POST /api/imports/{id}/confirm   (when canConfirm is true)    -> COMMITTED
   or POST /api/imports/{id}/cancel                              -> CANCELLED
```

- **Preview writes no clients and no sales.** It only stores the parsed rows (with the ID number protected: keyed hash
  and, for new clients, encrypted) so confirm can apply them.
- A preview **expires after 24 hours**.
- After confirm or cancel, the personal data in the staged rows (names, ID hash, encrypted ID) is **deleted**. The
  counts, issues and masked last four digits stay, so the import history remains readable.

## 3. Import states

| State | Meaning |
|---|---|
| `PREVIEWED` | uploaded and checked; can be mapped, confirmed or cancelled |
| `COMMITTED` | confirmed: clients and sales are saved |
| `CANCELLED` | thrown away |
| `EXPIRED` | a `PREVIEWED` import older than 24 hours (shown as EXPIRED; cannot be confirmed) |

---

## 4. Endpoints

### GET /api/imports/weekly-register/week-guess?fileName=...

Reads the week from a file name such as `Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx`, before uploading.
Accepted forms: `14 - 18 Sept 2026`, `14 September 2026`, `14 Sep 2026`, `2026-09-14`. Any date is moved back to its Monday.

**200 OK**
```json
{ "weekStarting": "2026-09-14" }
```
`weekStarting` is `null` when no date could be read: the screen must then ask for the week.

### POST /api/imports/weekly-register/preview

**Request:** `multipart/form-data` with one field `file` (the `.xlsx`). Optional query parameter
`weekStarting=2026-09-14` (a Monday); without it the week is read from the file name.

Frontend note: send a `FormData` and do **not** set `Content-Type` yourself (the browser adds the multipart boundary).
The CSRF header is still required.

**201 Created** - the **Import summary** (section 5).

**Errors**

| Status | Type | When |
|---|---|---|
| 400 | `import:week-required` | no `weekStarting` and none in the file name |
| 400 | `import:week-not-monday` | `weekStarting` is not a Monday |
| 400 | `file:empty`, `file:corrupt`, `file:not-xlsx`, `file:old-format`, `file:encrypted`, `file:macro-enabled` | the file is not a usable `.xlsx` |
| 400 | `file:unreadable` | the upload itself could not be read |
| 413 | `file:too-large` | larger than 10 MB (but see section 8: the upload limit is currently 1 MB) |
| 409 | `import:duplicate-file` | exactly this file (same SHA-256) was already **committed** |

### GET /api/imports?page=0&size=20

Import history, newest first. **200 OK** - a page of:
```json
{
  "id": 12,
  "fileName": "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx",
  "state": "COMMITTED",
  "weekStarting": "2026-09-14",
  "uploadedBy": "manager@example.com",
  "createdAt": "2026-09-19T08:02:11Z",
  "committedAt": "2026-09-19T08:10:40Z",
  "totalRows": 84,
  "rejectedRows": 2,
  "appliedRows": 82,
  "replacesImportJobId": null
}
```

### GET /api/imports/{id}

**200 OK** - the **Import summary** (section 5). **404** `import:not-found`.

### GET /api/imports/{id}/rows?sheet=Monday&action=NEW&page=0&size=50

The staged rows, paged (`size` max 200). `sheet` and `action` are optional filters.

**200 OK** - a page of:
```json
{
  "id": 501,
  "sheetName": "Monday",
  "rowNumber": 5,
  "date": "2026-09-14",
  "action": "NEW",
  "outcome": null,
  "idNumberMasked": "*********0089",
  "firstName": "Lerato",
  "lastName": "Mokoena",
  "consultantName": "Thandi X",
  "employerName": "PHUMELELA LOCAL",
  "quotation": 2400.00,
  "banked": 2175.00,
  "products": { "INSURANCE": 225.00, "MAFORI_CARD": 175.00 },
  "warnings": 1
}
```

| `action` | Meaning |
|---|---|
| `NEW` | no client with this ID number: confirm creates one |
| `UNCHANGED` | the client exists: the sale is added to them; their details are not changed |
| `DUPLICATE` | the same client appears again in this file: one more sale for the same person |
| `INVALID` | the row has an error and is **not** imported |

- `outcome` is `null` until confirm, then `APPLIED`.
- `rowNumber` is the row as the person sees it in Excel.
- After confirm or cancel, `firstName` and `lastName` are `null` (personal data deleted, section 2).
- `products` uses the product codes from `/api/products`.

### GET /api/imports/{id}/issues?severity=WARNING&page=0&size=50

Errors, warnings and notes found while checking, paged. `severity` (`ERROR`, `WARNING`, `INFO`) is optional.

**200 OK** - a page of:
```json
{
  "sheetName": "Monday",
  "rowNumber": 7,
  "field": "ID NUMBER",
  "code": "row:invalid-id-number",
  "severity": "ERROR",
  "message": "Not a valid SA ID number: wrong check digit."
}
```

- `sheetName` and `rowNumber` are `null` for issues about the whole file or sheet. An `ERROR` without a row
  **blocks** the import (`SHEET_ERRORS`); an `ERROR` on a row only rejects that row.
- `message` is safe to show: it never contains an ID number (the database refuses one).
- Common codes: `row:missing-id-number`, `row:invalid-id-number`, `row:id-leading-zero-restored`, `row:missing-name`,
  `row:invalid-amount`, `row:no-consultant`, `row:repeat-client`, `sheet:missing-day`, `sheet:missing-header`,
  `sheet:quotation-rule-differs`, `sheet:banked-rule-differs`, `client:name-differs`, `client:consultant-differs`,
  `row:consultant-not-active`, `lookup:new-employer`, `lookup:new-structure`, `lookup:new-payment-term`.
  The full list is in `imports/register/IssueCodes.java`.

### POST /api/imports/{id}/consultant-mappings

Matches a consultant name as written in the sheet to a consultant. It is **remembered for later imports**.

**Request**
```json
{ "nameInSheet": "Thandi X", "consultantId": 4 }
```
**200 OK** - the updated **Import summary**: the name disappears from `unmappedConsultants`.

**Errors:** `400` invalid fields, `404` `consultant:not-found`, `409` `import:name-already-mapped`,
`409` `import:not-previewed` or `import:expired`, `422` `import:name-not-in-import` (no row has that name).

Audited as `CONSULTANT_ALIAS_CREATED`.

### POST /api/imports/{id}/confirm

Applies the whole import in **one transaction**: if anything fails, nothing is saved.

**Request**
```json
{ "fileDigest": "3f6c...64 hex characters...", "replaceReason": null }
```
- `fileDigest`: copy it from the summary. It proves you are confirming the file you previewed.
- `replaceReason`: required only when replacing a week that was already imported (section 7), max 500.

**200 OK**
```json
{
  "importJobId": 12,
  "reportingWeekId": 3,
  "weekStarting": "2026-09-14",
  "transactions": 82,
  "productLines": 140,
  "extraValues": 6,
  "newClients": 61,
  "clientsAssigned": 58,
  "employersCreated": 2,
  "structuresCreated": 0,
  "paymentTermsCreated": 0,
  "staleRows": 0,
  "replacedImportJobId": null,
  "supersededTransactions": 0
}
```

What confirm does:

- creates the `NEW` clients (assigned to the row's consultant if that consultant is ACTIVE, otherwise unassigned),
  with `IMPORT` assignment history
- an existing client **without** a consultant is assigned to the row's ACTIVE consultant; an existing client with a
  consultant is **never** moved (the sale is still recorded for the consultant in the sheet)
- saves one sale per row, its product lines, and any extra columns; creates new employers, structures and payment terms
- audits `IMPORT_CONFIRMED` (and `WEEK_REPLACED` when replacing)

**Errors**

| Status | Type | When |
|---|---|---|
| 400 | `validation:invalid-request` | `fileDigest` missing or not 64 hex characters |
| 403 | `import:week-already-imported` | the week was already imported and the caller is not ADMIN |
| 409 | `import:digest-mismatch` | `fileDigest` is not this import's file |
| 409 | `import:duplicate-file` | the same file was committed in the meantime |
| 409 | `import:not-previewed`, `import:expired` | already confirmed / cancelled, or older than 24 hours |
| 409 | `import:stale-preview` | a client in the preview was removed since; upload again |
| 422 | `import:blocking-errors` | there are sheet-level errors (`SHEET_ERRORS`) |
| 422 | `import:unmapped-consultants` | some consultant names are not mapped yet |
| 422 | `import:replace-reason-required` | replacing a week without a reason |

### POST /api/imports/{id}/cancel

Throws the preview away. **200 OK** - the summary with `state: "CANCELLED"`.
**Errors:** `404`, `409` `import:not-previewed` / `import:expired`. Audited as `IMPORT_CANCELLED`.

---

## 5. The Import summary

Returned by preview, `GET /api/imports/{id}`, consultant mappings and cancel.

```json
{
  "id": 12,
  "fileName": "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx",
  "fileDigest": "3f6c...",
  "state": "PREVIEWED",
  "weekStarting": "2026-09-14",
  "weekEnding": "2026-09-20",
  "uploadedBy": "manager@example.com",
  "createdAt": "2026-09-19T08:02:11Z",
  "expiresAt": "2026-09-20T08:02:11Z",
  "committedAt": null,
  "totalRows": 84,
  "newClients": 61,
  "existingClients": 15,
  "repeatRows": 6,
  "rejectedRows": 2,
  "appliedRows": 0,
  "errors": 2,
  "warnings": 9,
  "infos": 3,
  "quotationTotal": 512340.00,
  "bankedTotal": 431200.00,
  "days": [
    { "sheetName": "Monday", "dayOfWeek": 1, "date": "2026-09-14", "rows": 12, "rejected": 0,
      "quotation": 81200.00, "banked": 70100.00 }
  ],
  "unmappedConsultants": [ { "nameInSheet": "Thandi X", "rows": 4 } ],
  "weekImportedByJobId": null,
  "replaceRequired": false,
  "canConfirm": false,
  "blockers": ["UNMAPPED_CONSULTANTS"]
}
```

- `days` always has **7 entries**, Monday to Sunday; a day without a sheet has `sheetName: null` and zeros.
- `newClients` / `existingClients` / `repeatRows` / `rejectedRows` match the row actions `NEW` / `UNCHANGED` /
  `DUPLICATE` / `INVALID`.
- `weekImportedByJobId`: another import already holds this week. `replaceRequired` is then `true` while previewed:
  confirming replaces the week (ADMIN, with a reason).
- **Enable the Confirm button only when `canConfirm` is `true`.** Otherwise show what `blockers` says:

| Blocker | What the user must do |
|---|---|
| `UNMAPPED_CONSULTANTS` | map each name in `unmappedConsultants` (consultant-mappings) |
| `SHEET_ERRORS` | fix the workbook (see issues without a row) and upload it again |
| `EXPIRED` | upload the file again |
| `NOT_PREVIEWED` | nothing: the import is already confirmed or cancelled |

## 6. Money

All amounts are decimal numbers in rand with 2 decimals (`2175.00`). Show them as they are - do not add them up
in the browser; the totals are in the summary.

## 7. Replacing a week

A week can only be imported once. Uploading a register for a week that already has a committed import gives a
summary with `replaceRequired: true`. Only an **ADMIN** can confirm it, and only with a `replaceReason`. The earlier
import's sales are kept but marked as replaced, so reports use the new ones.

## 8. Known issues (to fix)

- **Upload size:** the reader allows 10 MB, but Spring's default multipart limit is **1 MB**, so a larger workbook
  is refused before the import code sees it. Needs `spring.servlet.multipart.max-file-size=10MB` and
  `spring.servlet.multipart.max-request-size=11MB` in `application.properties`.
- **`Location` header** of the preview response is `/api/imports12` (missing `/`). The body is correct.
- **Same ID number, different name:** currently only a `client:name-differs` WARNING; the sale is still recorded for
  the existing client. To be changed into a blocker that needs a manager's explicit confirmation.
- **Per-client audit:** clients created by confirm have no `CLIENT_CREATED` audit entry of their own yet.
