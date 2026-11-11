# API contract - weekly register import

Owner: Sifiso (import). Written up by Ntiyiso from the code on `main` (commit `8e12fe8`). Consumers: Jack (frontend).
Status: DRAFT - implemented in `imports/weekly`. Sifiso: please check it matches what you intended.

The general rules of `docs/api-auth-consultants-clients.md` section 1 apply: session cookie, CSRF header on every
`POST`, ProblemDetail errors (`urn:credit-advisory:<code>`), paging shape, and **full ID numbers never appear** -
rows only ever show `idNumberMasked`.

---

## 1. Who can use it

**ADMIN only**, for every endpoint below (business decision, 9 November 2026: only the admin runs imports and adds
staff). A MANAGER or CONSULTANT gets `403`. The spec allowed managers too; this narrows it.
Replacing a week that was already imported also needs a reason (section 7).

## 2. The flow

```
1. GET  /api/imports/weekly-register/week-guess?fileName=...   (optional: pre-fill the week)
2. POST /api/imports/weekly-register/preview                    upload -> import job in state PREVIEWED
3. GET  /api/imports/{id}                                       summary: counts, days, blockers
   GET  /api/imports/{id}/rows      GET /api/imports/{id}/issues  (details, paged)
4. POST /api/imports/{id}/consultant-mappings                   per unknown consultant name: map it,
   or POST /api/imports/{id}/new-consultants                       or create it as a new consultant
5. POST /api/imports/{id}/confirm   (when canConfirm is true)    -> COMMITTED
   or POST /api/imports/{id}/cancel                              -> CANCELLED
```

- **Preview writes no clients and no sales.** It only stores the parsed rows (with the ID number protected: keyed hash
  and, for new clients, encrypted) so confirm can apply them.
- A preview **expires after 24 hours**. A clean-up job (every hour) then marks it `EXPIRED` and deletes its staged
  names and ID numbers (audited as `IMPORT_EXPIRED`); only the last four ID digits, the counts, the issues and the
  workbook-check figures stay.
- After confirm or cancel, the personal data in the staged rows (names, ID hash, encrypted ID) is **deleted**. The
  counts, issues and masked last four digits stay, so the import history remains readable.

## 3. Import states

| State | Meaning |
|---|---|
| `PREVIEWED` | uploaded and checked; can be mapped, confirmed or cancelled |
| `COMMITTED` | confirmed: clients and sales are saved |
| `CANCELLED` | thrown away |
| `EXPIRED` | a `PREVIEWED` import older than 24 hours: cannot be confirmed. Shown at once; the clean-up job then stores it and deletes the staged personal data |

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
  "uploadedBy": "admin@example.com",
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
  `client:unassigned-by-manager`, `row:consultant-not-active`, `lookup:new-employer`, `lookup:new-structure`,
  `lookup:new-payment-term`. The full list is in `imports/register/IssueCodes.java`.
- `client:name-differs` (WARNING): the row's ID number belongs to a client stored under another name. It makes the
  `NAME_DIFFERENCES` blocker (section 5): the admin must check these rows before confirming.
- `client:unassigned-by-manager` (WARNING): a manager removed this client's consultant; the import will **not**
  assign the client again.
- `row:no-client` (WARNING, with a row number): the row has a consultant or a typed-in amount but no name, surname
  or ID number, so it is **not** imported. The workbook's own Report sheet may still count it. Rows that are empty,
  hold only zeros, or only formulas (a totals row) are not reported.

### GET /api/imports/{id}/issues/export?severity=ERROR

The same issues as a **file to download** (FR-12), for fixing the workbook in Excel. `severity` is optional.

**200 OK** - `Content-Type: text/csv; charset=UTF-8`,
`Content-Disposition: attachment; filename="import-12-2026-09-14-issues.csv"`:
```
"Sheet","Row","Field","Severity","Code","Message","ID number (last 4)"
"Monday","7","ID NUMBER","ERROR","row:invalid-id-number","Not a valid SA ID number: wrong check digit.","*********1234"
```
- Errors first, then warnings and notes, in the same order as the list above.
- **No names and no full ID numbers**: only the last four digits, so the row can be found in the sheet.
- Opens directly in Excel (UTF-8 with a byte-order mark). A value Excel would treat as a formula is written with a
  leading `'`.
- Works for previews and for finished imports (the masked ID stays after personal data is purged).
- Audited as `IMPORT_ISSUES_EXPORTED`. **Errors:** `404` `import:not-found`.

Frontend: a "Download issues" button next to the issues list, linking straight to this URL (the session cookie
goes with it; it's a GET, so no CSRF header is needed).

### POST /api/imports/{id}/consultant-mappings

Matches a consultant name as written in the sheet to a consultant. It is **remembered for later imports**.

**Request**
```json
{ "nameInSheet": "Thandi X", "consultantId": 4 }
```
**200 OK** - the updated **Import summary**: the name disappears from `unmappedConsultants`.

**Errors:** `400` invalid fields, `404` `consultant:not-found`, `409` `import:name-already-mapped`,
`409` `import:not-previewed` or `import:expired`, `422` `import:name-not-in-import` (no row has that name).

### POST /api/imports/{id}/new-consultants

The other way to resolve an unknown name: **create** it as a new consultant ("import the consultants"). For
people who aren't in the system yet. A typo of someone who is should be mapped instead (see `similarConsultants`).

**Request** (1-100 names, as in `unmappedConsultants`; case and spaces don't matter)
```json
{ "namesInSheet": ["AMBITIOUS", "VICTORIA"] }
```
**200 OK**
```json
{ "created": [ { "consultantId": 14, "consultantCode": "C0014", "fullName": "AMBITIOUS" },
               { "consultantId": 15, "consultantCode": "C0015", "fullName": "VICTORIA" } ],
  "summary": { "...": "the updated Import summary" } }
```
Each new consultant:
- gets a **generated code** and the name as written in the sheet; an admin can rename it later (add the surname);
- is `ACTIVE`, so the week's new clients are assigned to them at confirm;
- keeps the sheet's spelling as an alias, so next week's sheet still matches after a rename;
- gets **no login**: an admin adds one later (users screen or `POST /api/consultant-accounts`).

All or nothing. Audited as `CONSULTANT_CREATED` with `importJobId`. **Who:** ADMIN (only the admin adds staff).

**Errors:** `400` empty list or over 100, `422` `import:no-names` (a blank name), `422` `import:name-not-in-import`,
`409` `import:name-already-mapped` (it already matches someone), `409` `import:not-previewed` / `import:expired`.

Frontend: for each unknown name, show the `similarConsultants` first ("Did you mean C0003 Ambitious Mokoena?" →
map), then **Create as new consultant**, with a "Create all remaining" button that sends every name left.

Audited as `CONSULTANT_ALIAS_CREATED`.

### POST /api/imports/{id}/confirm

Applies the whole import in **one transaction**: if anything fails, nothing is saved.

**Request**
```json
{ "fileDigest": "3f6c...64 hex characters...", "replaceReason": null, "acceptNameDifferences": false }
```
- `fileDigest`: copy it from the summary. It proves you are confirming the file you previewed.
- `replaceReason`: required only when replacing a week that was already imported (section 7), max 500.
- `acceptNameDifferences`: required (`true`) only when the summary has the `NAME_DIFFERENCES` blocker. Send it
  only after the admin has looked at the `client:name-differs` rows. The stored names are still never changed.

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
  "supersededTransactions": 0,
  "replacedTypedInSales": 3
}
```

What confirm does:

- creates the `NEW` clients (assigned to the row's consultant if that consultant is ACTIVE, otherwise unassigned),
  with `IMPORT` assignment history
- an existing client **without** a consultant is assigned to the row's ACTIVE consultant, **unless a manager
  unassigned it** (then it stays unassigned); an existing client with a consultant is **never** moved, also after a
  transfer, and also when the transfer happened while the preview was open. The sale is still recorded for the
  consultant in the sheet.
- writes a `CLIENT_CREATED` audit entry for **every** client it creates (`consultantId`, `importJobId`)
- saves one sale per row, its product lines, and any extra columns; creates new employers, structures and payment terms
- **replaces the sales typed into the app for this week** (the spreadsheet wins; `docs/api-sales.md` section 1):
  they are kept, marked as replaced, and no report counts them again. `replacedTypedInSales` says how many
- audits `IMPORT_CONFIRMED` (with `nameDifferencesAccepted` and `replacedTypedInSales`), and `WEEK_REPLACED` when
  replacing

**Errors**

| Status | Type | When |
|---|---|---|
| 400 | `validation:invalid-request` | `fileDigest` missing or not 64 hex characters |
| 403 | `import:week-already-imported` | the week was already imported and the caller is not ADMIN (only reachable if the role rule above is ever loosened) |
| 409 | `import:digest-mismatch` | `fileDigest` is not this import's file |
| 409 | `import:duplicate-file` | the same file was committed in the meantime |
| 409 | `import:not-previewed`, `import:expired` | already confirmed / cancelled, or older than 24 hours |
| 409 | `import:stale-preview` | a client in the preview was removed since; upload again |
| 422 | `import:blocking-errors` | there are sheet-level errors (`SHEET_ERRORS`) |
| 422 | `import:unmapped-consultants` | some consultant names are not mapped yet |
| 422 | `import:replace-reason-required` | replacing a week without a reason |
| 422 | `import:name-differences-unconfirmed` | `NAME_DIFFERENCES` and no `acceptNameDifferences: true` (checked after the role and reason checks) |

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
  "uploadedBy": "admin@example.com",
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
  "unmappedConsultants": [ { "nameInSheet": "Thandi X", "rows": 4,
                             "similarConsultants": [ { "consultantId": 4, "consultantCode": "C004",
                                                       "fullName": "Thandi Xundu", "status": "ACTIVE" } ] } ],
  "weekImportedByJobId": null,
  "replaceRequired": false,
  "canConfirm": false,
  "blockers": ["UNMAPPED_CONSULTANTS"],
  "reconciliationStatus": "DIFFERENCES",
  "reportDifferences": 77,
  "nameDifferences": 0,
  "typedInSales": 3
}
```

- `days` always has **7 entries**, Monday to Sunday; a day without a sheet has `sheetName: null` and zeros.
- `newClients` / `existingClients` / `repeatRows` / `rejectedRows` match the row actions `NEW` / `UNCHANGED` /
  `DUPLICATE` / `INVALID`.
- `weekImportedByJobId`: another import already holds this week. `replaceRequired` is then `true` while previewed:
  confirming replaces the week (ADMIN, with a reason).
- `typedInSales`: how many sales were typed into the app for this week (`docs/api-sales.md`). Confirming replaces
  them with the spreadsheet's rows. **Never** a blocker, but show it next to the Confirm button, so whoever confirms
  first checks that the spreadsheet has every sale of the week. `0` once the import is no longer previewed.
- `reconciliationStatus` / `reportDifferences` (stage 4): how the workbook's own Report and Breakdown figures
  compare with the recalculated ones: `NOT_CHECKED` (the file has neither sheet), `MATCHED` or `DIFFERENCES`.
  **Never** a blocker; see section 8.
- **Enable the Confirm button only when `canConfirm` is `true`.** Otherwise show what `blockers` says:

| Blocker | What the user must do |
|---|---|
| `UNMAPPED_CONSULTANTS` | for each name in `unmappedConsultants`: map it (consultant-mappings) or create it (new-consultants) |
| `SHEET_ERRORS` | fix the workbook (see issues without a row) and upload it again |
| `NAME_DIFFERENCES` | check the `nameDifferences` rows (issues with code `client:name-differs`), then either fix the workbook and upload again, or confirm with `acceptNameDifferences: true`. Show it as a tick box ("I checked these N clients"), not as a hard stop |
| `EXPIRED` | upload the file again |
| `NOT_PREVIEWED` | nothing: the import is already confirmed or cancelled |

## 6. Money

All amounts are decimal numbers in rand with 2 decimals (`2175.00`). Show them as they are - do not add them up
in the browser; the totals are in the summary.

## 7. Replacing a week

A week can only be imported once. Uploading a register for a week that already has a committed import gives a
summary with `replaceRequired: true`. It can only be confirmed with a `replaceReason`. The earlier
import's sales are kept but marked as replaced, so reports use the new ones.

Sales typed into the app (`docs/api-sales.md`) are replaced the same way by the **first** import of their week,
without a reason: that is the normal flow, not a correction. After that, the week takes no more typed-in sales.

## 8. Workbook check: the Report and Breakdown sheets (stage 4)

The app always uses figures **recalculated from the client rows**. The preview also reads every formula cell of
the **Report** and **Breakdown** sheets and keeps it next to what it should have shown, so the admin can see which
spreadsheet formulas were wrong. This is evidence only: it **never blocks confirm**, and nothing in it is personal
data (only totals, staff and structure labels, and formulas). Same role as above (ADMIN).

### GET /api/imports/{id}/reconciliation
```json
{
  "importJobId": 7, "status": "DIFFERENCES", "figures": 1359, "compared": 1359, "differences": 77,
  "sections": [ { "sheetName": "Report", "section": "DAILY_CLIENT_COUNT", "figures": 8, "compared": 8,
                  "differences": 5 }, "…" ],
  "defects": [ { "defectCode": "ROW_OFFSET", "figures": 88, "differences": 18,
                 "description": "The formula uses another row's label, so it shows another consultant's (or structure's) figure." }, "…" ]
}
```
- `figures`: formula cells read; `compared`: the ones the import could recalculate; `differences`: cells
  showing a wrong figure this week, plus error cells (`#REF!`, `#VALUE!`).
- `defects[].figures` counts every cell whose **formula** is wrong; `defects[].differences` only those that
  show a wrong number **this week**. A wrong formula can give the right number by luck (for example a Saturday with
  no sales), so it is listed but not counted as a difference.

### GET /api/imports/{id}/report-figures?sheet=Report&section=DAILY_BANKED&defect=WRONG_SHEET&onlyDifferences=true&page=0&size=100
All filters are optional. `size` is at most 500. Sorted Report first, then Breakdown, in sheet order.
```json
{ "id": 9, "sheetName": "Report", "section": "DAILY_BANKED", "blockTitle": "BANKED", "rowLabel": "Sunday",
  "columnLabel": "BANKED", "dayOfWeek": 7, "consultantId": null, "productCode": null, "structureId": null,
  "cellRef": "C9", "sheetValue": 0.00, "sheetText": null, "sheetError": null, "formula": "=SUM(Saturday!T:T)",
  "computedValue": 8275.00, "computedText": null, "difference": -8275.00, "matches": false,
  "defectCode": "WRONG_SHEET", "defectDescription": "The formula reads another day's sheet." }
```
- `difference` = `sheetValue` − `computedValue`. `matches` is `null` when there is nothing to compare.
- `consultantId` / `structureId` are filled when the row label matches a consultant (or alias) or a structure.
- `productCode` is what the formula **actually** adds up (the CARD block shows `IVC`).
- Text cells ("Target Reached") are compared as text: `sheetText` against `computedText`.

| Status | type | When |
| --- | --- | --- |
| 400 | `reconciliation:invalid-sheet` / `-section` / `-defect` | unknown filter value |
| 404 | `import:not-found` | |

**Sections**: `DAILY_BANKED`, `DAILY_QUOTATION`, `DAILY_TARGET`, `DAILY_CLIENT_COUNT`, `CONSULTANT_PRODUCT`,
`CONSULTANT_CLIENT_COUNT`, `CONSULTANT_QUOTATION`, `CONSULTANT_TOTAL`, `FINANCE`, `SHEET_DIFFERENCE`,
`BUDGET_BY_STRUCTURE`, `BREAKDOWN`, `BREAKDOWN_SUMMARY`, `OTHER`.

**Defect codes** (the `description` field carries the wording for the screen):

| Code | Meaning |
| --- | --- |
| `WRONG_COLUMN` | adds up the wrong column (another product or field) |
| `WRONG_CRITERIA_COLUMN` | looks for the consultant (or structure) in the wrong column |
| `ROW_OFFSET` | uses another row's label, so it shows someone else's figure |
| `WRONG_SHEET` | reads another day's sheet |
| `TEXT_ID_NOT_COUNTED` | `COUNT()` skips ID numbers stored as text |
| `RANGE_INCOMPLETE` | a total leaves out cells it should add up |
| `REF_ERROR` / `VALUE_ERROR` | `#REF!` / `#VALUE!` |
| `BLANK_CONSULTANT_EXCLUDED` | a total adds only the listed consultants; rows without one (or with an unlisted one) are left out |
| `THRESHOLD_GREATER_THAN` | the target test uses ">", so exactly reaching the target shows "Not Reached" |
| `UNEXPLAINED` | differs for a reason the import could not identify |

Suggested screen (Jack): on the preview, a "Workbook check" card with `reconciliationStatus` and
`reportDifferences`; it opens the summary (sections and defects) and a table from `report-figures` with
`onlyDifferences=true`, showing cell, labels, sheet value, correct value, difference and the defect description.

## 9. Known issues (to fix)

Fixed since the draft: the upload size (`spring.servlet.multipart.*` is now in `application.properties`) and the
preview's `Location` header (now `/api/imports/12`).

Also done: **same ID number, different name** is now the `NAME_DIFFERENCES` blocker, and confirm writes a
`CLIENT_CREATED` audit entry per client. Nothing open on the import side right now.

## 10. Consultant name spellings (aliases)

When a preview maps a name from the sheet to a consultant (section 4, consultant mappings), the spelling is saved as
an **alias** so later imports match it automatically. These endpoints list them, add one before an import, and
remove a wrong one. **Who:** ADMIN.

### GET /api/consultant-aliases?consultantId=&search=&page=0&size=50
```json
{ "items": [ { "id": 4, "alias": "AMBITIOUS", "consultantId": 3, "consultantCode": "C003",
               "consultantName": "Ambitious Example", "consultantStatus": "ACTIVE",
               "createdBy": "admin@mafori.co.za", "createdAt": "2026-11-09T08:12:00Z", "transactions": 41 } ],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1 }
```
`transactions`: how many imported sales had this spelling, so you can see whether it is in use. `search` matches
the spelling or the consultant's name. Sorted by consultant, then spelling.

### POST /api/consultant-aliases
`{ "consultantId": 3, "alias": "AMBI" }` → `201` with the alias. Spaces are tidied.

| Status | type | When |
| --- | --- | --- |
| 409 | `consultant-alias:taken` | the spelling already matches a consultant (their name or another alias), ignoring case and spaces |
| 422 | `consultant-alias:invalid` / `consultant-alias:consultant-required` | empty or over 200 characters / no consultant |
| 404 | `consultant:not-found` | |

### DELETE /api/consultant-aliases/{id}
`204`. Affects **later imports only**: sales already imported keep their consultant. An open preview that used the
spelling shows it under `unmappedConsultants` again, and confirm refuses until it is mapped. Audited as
`CONSULTANT_ALIAS_REMOVED` (adding: `CONSULTANT_ALIAS_CREATED`). `404 consultant-alias:not-found`.

Frontend: a "Name spellings" tab on the consultant page (filter `consultantId`), with remove buttons.

