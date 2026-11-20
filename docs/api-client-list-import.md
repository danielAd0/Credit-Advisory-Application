# Client list import API (first load of the full client list)

The launch client list is loaded **once, before the first weekly register import**: every client the business
already has, with their details and consultant. After that, the weekly register finds these clients by ID number.

It works like the weekly register import: **preview** (nothing changes), check, **confirm** (one transaction).
The staged names, ID numbers and phone numbers are deleted when the import ends (confirmed, cancelled or expired
after 24 hours).

**Who:** ADMIN only (only the admin runs imports). MANAGER and CONSULTANT get `403`, signed out `401`.

Built by Sifiso: `backend/.../imports/clientlist/`, migration V29. Nothing in the weekly register import changed.

---

## 1. Endpoints

| Method | Path | What |
| --- | --- | --- |
| POST | `/api/imports/client-list/preview` | Upload the list (`multipart/form-data`, part `file`, `.xlsx`; an `.xlsb` is refused with `file:binary-format`, so save it as `.xlsx` in Excel first). **201** + the summary |
| GET | `/api/imports/client-list` | Client list imports, newest first (paged) |
| GET | `/api/imports/client-list/{id}` | The summary again |
| GET | `/api/imports/client-list/{id}/rows?action=` | Staged rows (paged). `action`: NEW, UPDATE, UNCHANGED, CONFLICT, DUPLICATE, INVALID |
| GET | `/api/imports/client-list/{id}/issues?severity=` | Errors, warnings, info (paged) |
| GET | `/api/imports/client-list/{id}/issues/export?severity=` | The issues as a CSV file |
| POST | `/api/imports/client-list/{id}/confirm` | Import it |
| POST | `/api/imports/client-list/{id}/cancel` | Drop the preview and its staged data |

Client list imports do **not** appear in `/api/imports` (the weekly register list), and the other way round.

## 2. What the file must look like

- One visible sheet with a **header row** in its first 20 rows. Rows above it (a title) are fine.
- Columns are found by their **header text**, in any order. Spellings are rows in `client_list_header_aliases`
  (V29, V30); a new spelling is one row in a new migration.
- **The real launch list** has the header row `FILNO, IDNO, SURNAME, NAME, C_EMPLOYER, WORK_TEL, HOME_TEL, CELL_TEL,
  C_BRANCHNAME, FILEID`. All ten are recognised (V30 added `IDNO`, `FILNO`, `WORK_TEL`, `HOME_TEL`, `CELL_TEL`). It
  has **no consultant and no email column**, so every new client arrives without a consultant: they show on the
  Overview under "Waiting for a consultant", and a manager assigns them in Reassign clients.

| Field | Required | Headers accepted (examples) | Stored as |
| --- | --- | --- | --- |
| Name | yes | Name, First name, Firstname | `first_name` |
| Surname | yes | Surname, Last name | `last_name` |
| ID number | yes | ID number, ID No, IDNO, IDNumber, ID | encrypted + keyed hash, last 4 digits |
| Consultant | no | Consultant, Consultant name | the client's consultant |
| Consultant email | no | Consultant email | identifies the consultant (section 4) |
| Email | no | Email, E-mail, Email address | the **client's** email |
| Cellphone | no | Cell, Cellphone, CELL_TEL, Mobile, Phone | `phone`, as +27... |
| Work phone | no | Work, Work phone, WORK_TEL, Tel (W) | `work_phone`, as +27... |
| Home phone | no | Home, Home phone, HOME_TEL, Tel (H) | `home_phone`, as +27... |
| Employer | no | Employer, C_EMPLOYER | `employer_id`; "DEBIT ORDER ..." means payment method DEBIT_ORDER, no employer |
| Product quotation | no | C_BRANCHNAME, Branch, Product quotation | `product_quotation_id` |
| File number | no | File number, File No, FILNO | `file_number` (digits) |
| File ID | no | File ID, FILEID | `file_id` (digits) |

Any other column (for example **Date**) is listed as "not imported" (info) and read past.

**Plain "Email" is the client's address** (assumption: please confirm; the real launch list has no email column,
so it does not affect that file). The weekly register's email column is the
consultant's. If the launch list's Email is the consultant's, it is one row in a migration:
`UPDATE client_list_header_aliases SET field = 'CONSULTANT_EMAIL' WHERE alias = 'EMAIL';`

## 3. What happens to each row

| Action | When | At confirm |
| --- | --- | --- |
| `NEW` | The ID number is not stored yet | A new client with every field the row has. Assigned to the consultant if that consultant is ACTIVE |
| `UPDATE` | Stored under the **same** name, and the row has a field the client does not have yet | Only the empty fields are filled in. Nothing stored is overwritten |
| `UNCHANGED` | Stored under the same name, nothing to add | Nothing (except: an unassigned client gets the list's consultant) |
| `CONFLICT` | Stored under **another** name | **Not imported.** A person checks the client |
| `DUPLICATE` | The same ID number higher up in the sheet | Not imported; the first row is |
| `INVALID` | No name, no surname, or no valid SA ID number | Not imported |

- A client who already has a consultant **keeps** them (`client:consultant-differs`).
- A client whose consultant a manager removed is **not** assigned again (`client:unassigned-by-manager`).
- Phones are stored as `+27...`. A number cell that lost its leading zero (821234567) gets it back.
- A value that cannot be stored (a phone that is not a phone, an email without `@`, a File number with letters) is
  left out with a **warning**; the client is still imported.
- Text that was saved with the wrong encoding somewhere before the file reached us (`AIRLOOM DÃƒâ€šÃ‚°COR` instead of
  `AIRLOOM DÉCOR`) gets a `row:garbled-text` **warning** for that column, and is imported as it is. It matters most
  for employers: every garbled spelling becomes an employer of its own. The issues CSV lists every such row, so the
  file can be fixed before confirm. Real accents (`Zoë`, `São`, `CAFÉ`) are not flagged.
- Employers that match no employer or spelling are **created at confirm, marked needs review** (as the weekly
  register does). Product quotations that are not in the list are **added** at confirm.
- Every new client: audit `CLIENT_CREATED` (`source: CLIENT_LIST`). Every filled-in client: `CLIENT_UPDATED` with
  the field names (never the values). Each assignment: `client_assignment_history` type `IMPORT`.

## 4. Consultants

Same rules as the weekly register (`ConsultantResolver`, `docs/consultant-onboarding.md` section 2): matched by
email, then by name or alias. A name that matches nobody is **registered** at confirm (generated code, ACTIVE, no
login). A name that matches two consultants is a **conflict** and blocks confirm.

The summary's `consultants` lists them. **Confirm sends back `consultants.fingerprint`**: if the list changed since
the preview (a consultant added, renamed or suspended), confirm is refused with `409 import:consultants-changed`
and the screen reloads the summary. This replaces the weekly register's separate "Confirm consultants" step.

## 5. The summary

```json
{
  "id": 41, "state": "PREVIEWED", "fileName": "Client list.xlsx", "fileDigest": "9f2c…", "sheetName": "Clients",
  "uploadedBy": "admin@mafori.co.za", "createdAt": "…", "expiresAt": "…", "committedAt": null,
  "rows": { "total": 1520, "newClients": 1490, "update": 0, "unchanged": 0, "conflict": 0, "duplicate": 12, "invalid": 18 },
  "issues": { "errors": 18, "warnings": 40, "info": 3 },
  "columns": [ { "header": "Name", "field": "NAME" }, { "header": "Date", "field": null } ],
  "consultants": {
    "fingerprint": "46c1…",
    "matched": [ { "consultantId": 1, "consultantCode": "C0001", "fullName": "…", "status": "ACTIVE", "namesInSheet": ["…"], "rows": 210 } ],
    "toRegister": [ { "fullName": "…", "email": null, "otherSpellings": [], "rows": 35 } ],
    "emailsToAdd": [], "conflicts": []
  },
  "additions": { "debitOrders": 60, "withCellphone": 1400, "withWorkPhone": 300, "withHomePhone": 90, "withEmail": 500,
                 "withFileNumber": 1490, "withFileId": 1490, "withEmployer": 1400, "withProductQuotation": 1490, "withoutConsultant": 20 },
  "newEmployers": ["…"], "newProductQuotations": ["…"],
  "blockers": [], "canConfirm": true, "result": null
}
```

`blockers`: `FILE_ERRORS` (a file-level error: no list, two lists, two columns for one field, no rows),
`CONSULTANT_CONFLICTS`, `NOTHING_TO_IMPORT`, `EXPIRED`, `FINISHED`. After confirm, `result` has `newClients`,
`appliedRows`, `staleRows`, `consultantsRegistered`, `clientsLinked`.

## 6. Confirm

`POST /api/imports/client-list/{id}/confirm`
```json
{ "fileDigest": "<summary.fileDigest>", "consultantsFingerprint": "<summary.consultants.fingerprint>" }
```
**200**
```json
{ "importJobId": 41, "newClients": 1490, "updatedClients": 0, "unchangedClients": 0, "assigned": 1470,
  "newClientsWithoutConsultant": 20, "consultantsRegistered": 3, "consultantEmailsAdded": 0, "consultantAliasesAdded": 0,
  "employersCreated": 85, "productQuotationsCreated": 6, "staleRows": 0, "skippedRows": 30 }
```

| Status | `type` | When |
| --- | --- | --- |
| 400 | `validation:invalid-request` | `fileDigest` or `consultantsFingerprint` missing or not 64 hex characters |
| 404 | `import:not-found` | no client list import with that id |
| 409 | `import:digest-mismatch` | the digest is not this import's file |
| 409 | `import:consultants-changed` | the consultant list changed since the preview |
| 409 | `import:not-previewed` / `import:expired` | already confirmed or cancelled / older than 24 hours |
| 409 | `import:duplicate-file` | this exact file was already imported |
| 409 | `import:stale-preview` | a client or consultant changed in a way the preview did not allow for; upload again |
| 422 | `import:blocking-errors` | file-level errors |
| 422 | `import:consultant-conflicts` | a consultant name cannot be matched safely |
| 422 | `import:nothing-to-import` | no row can be imported |

Uploading the **same list again** (a different file) is safe: every client is then `UNCHANGED` or `UPDATE`, and
nothing is created twice.

## 7. Issue codes

| Code | Severity | Meaning |
| --- | --- | --- |
| `file:no-client-list` | ERROR | No sheet has Name, Surname and ID number in a header row |
| `file:several-client-lists` | ERROR | More than one sheet looks like a client list |
| `sheet:duplicate-column` | ERROR | Two columns are the same field |
| `sheet:empty` | ERROR | Header but no rows |
| `sheet:unknown-column` | INFO | A column that is not imported |
| `file:new-employers`, `file:new-product-quotations` | INFO | Created at confirm |
| `row:missing-name`, `row:missing-surname`, `row:missing-id-number`, `row:invalid-id-number` | ERROR | Row not imported |
| `row:value-too-long` | ERROR (name) / WARNING (others) | Longer than the column |
| `row:id-leading-zero-restored` | WARNING | ID number typed as a number; the zero was added back |
| `row:phone-leading-zero-restored` | INFO | Same for a phone |
| `row:invalid-phone`, `row:invalid-email`, `row:invalid-consultant-email`, `row:invalid-file-reference`, `row:email-without-consultant`, `row:formula-error-ignored` | WARNING | That value is left out |
| `row:garbled-text` | WARNING | Text saved with the wrong encoding before it reached us (for example `Ã©` where `é` belongs). Imported as it is; fix it in the file |
| `row:no-client` | WARNING | Values but no name, surname or ID number |
| `row:duplicate-id-number` | WARNING | Same ID number as an earlier row |
| `client:name-differs` | WARNING | Stored under another name: not imported |
| `client:consultant-differs`, `client:unassigned-by-manager`, `row:consultant-not-active` | WARNING | The assignment is not changed |

Messages never contain a name or an ID number; the CSV shows only the last four digits.

## 8. For the screen (Jack)

An **Admin > Import client list** page, separate from the weekly import:
1. Upload; show `rows`, `issues`, `columns` (which header became which field), `additions`.
2. Consultants: `matched`, `toRegister`, `conflicts` (conflicts must be fixed in the file).
3. New employers and product quotations, the issues table (and its CSV download), and a rows table filtered by
   `action`.
4. **Confirm** sends `fileDigest` and `consultants.fingerprint`; on `409 import:consultants-changed`, reload the
   summary and ask again. **Cancel** drops the preview.
