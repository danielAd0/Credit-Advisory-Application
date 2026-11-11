# API contract - typed-in sales (Consultant actions page)

Same conventions as `docs/api-auth-consultants-clients.md`: session cookie + `X-XSRF-TOKEN`, errors are
ProblemDetail JSON (`type` = `urn:credit-advisory:<code>`).

## 1. How typed-in sales and the weekly spreadsheet fit together

Consultants type a sale into the app on the day it happens, so managers see the week as it goes. **The same
sale is also in that week's spreadsheet**, and the business decided on 10 November that **the spreadsheet wins**:

1. During the week, typed-in sales count in the reports straight away. A week shows in the reports as soon as it
   has one typed-in sale, before any import.
2. When that week's spreadsheet is imported, confirming the import **replaces** the week's typed-in sales. They are
   kept in the database but marked as replaced, and no report counts them again, so nothing is counted twice. The
   import preview says how many it will replace (`typedInSales`, `docs/api-imports.md` section 5).
3. Once a week is imported, it takes **no more** typed-in sales (`409 sale:week-already-imported`): such a sale
   belongs in the spreadsheet, which is imported again.

The week of a sale is the Monday on or before its date.

## 2. Who can capture

| Role | Clients | Whose sale |
|---|---|---|
| CONSULTANT | only their own clients (others are `404`) | always their own; sending someone else's `consultantId` is `403` |
| MANAGER, ADMIN | every client | `consultantId` if sent, otherwise the client's current consultant |

The consultant must be ACTIVE.

## 3. Endpoints

### GET /api/payment-terms

**Who:** everyone signed in. **200** - the active payment terms, sorted by name:

```json
[ { "id": 1, "name": "Once off payment" }, { "id": 3, "name": "Within 12 months" } ]
```

### GET /api/structures

**Who:** everyone signed in. **200** - the active structures, sorted by name, spelled as in the weekly register:

```json
[ { "id": 3, "name": "MAFORI CREDIT" }, { "id": 2, "name": "VITAL CREDIT" } ]
```

### POST /api/transactions

**Who:** everyone signed in (see section 2).

**Headers:** `Idempotency-Key: <a new UUID for each sale>`. The page makes one when the form opens and a new one
after each saved sale. Sending the same key again (a double click, or a retry after a lost answer) saves nothing
and answers `200` with the sale saved the first time.

**Request** - amounts in rand, at most two decimals:

```json
{
  "clientId": 101,
  "consultantId": 4,
  "transactionDate": "2026-09-15",
  "lines": [
    { "productId": 1, "amount": 1500 },
    { "productId": 7, "amount": 300.5 }
  ],
  "amountToClient": 5000,
  "thirdPartySettlement": null,
  "paymentTermId": 1,
  "employerId": 12,
  "structureId": 2,
  "primaryStructureBudgetList": "VITAL CREDIT",
  "primaryStructureLoanAmount": 2500,
  "secondaryStructureBudgetList": null,
  "secondaryStructureLoanAmount": null
}
```

| Field | Rules |
|---|---|
| `clientId` | required; a client the caller may see |
| `consultantId` | optional; see section 2 |
| `transactionDate` | required; not after today (South African time) |
| `lines` | required, 1 to 30; each product at most once; `amount` more than 0. The product must be active |
| `amountToClient`, `thirdPartySettlement`, the two loan amounts | optional; 0 or more |
| `paymentTermId` | optional; an active payment term |
| `employerId` | optional; an active employer |
| `structureId` | optional; an active structure |
| the two budget lists | optional; max 500, trimmed, blank means none. The budget report groups by these |

**201 Created** (or **200** for a key used before):

```json
{
  "id": 9001,
  "clientId": 101,
  "consultantId": 4,
  "transactionDate": "2026-09-15",
  "weekStarting": "2026-09-14",
  "dayOfWeek": 2,
  "lines": [
    { "productId": 1, "name": "Combo", "amount": 1500.00, "countsTowardQuotation": true, "countsTowardBanked": true },
    { "productId": 7, "name": "Credit Repair", "amount": 300.50, "countsTowardQuotation": true, "countsTowardBanked": false }
  ],
  "quotationTotal": 1800.50,
  "bankedTotal": 1500.00,
  "replaced": false,
  "typedIn": true
}
```

`quotationTotal` and `bankedTotal` follow each product's "counts toward" settings, the same as the reports.
`replaced` is `true` once that week's spreadsheet has been imported. `typedIn` is `false` for a sale from an
import (only in `GET /api/transactions`). The response never holds an ID number or other client details.

**Errors**

| Status | Type | When |
|---|---|---|
| 400 | `validation:invalid-request` | a field breaks the rules above (each listed in `errors`) |
| 400 | `sale:idempotency-key-required` | no `Idempotency-Key` header |
| 400 | `sale:invalid-idempotency-key` | the header is not a UUID |
| 400 | `sale:duplicate-product` | the same product twice on one sale |
| 403 | `sale:cannot-capture-for-others` | a CONSULTANT sent someone else's `consultantId` |
| 403 | `sale:no-consultant-record` | a CONSULTANT login without a consultant record |
| 404 | `client:not-found` | no such client, or not visible to the caller |
| 404 | `consultant:not-found`, `product:not-found`, `employer:not-found`, `payment-term:not-found`, `structure:not-found` | an id that does not exist |
| 409 | `sale:week-already-imported` | that week's spreadsheet was already imported (section 1) |
| 422 | `sale:date-in-future` | `transactionDate` after today |
| 422 | `sale:consultant-required` | MANAGER / ADMIN, no `consultantId`, and the client has no consultant |
| 422 | `sale:consultant-not-active` | the consultant is INACTIVE or SUSPENDED |
| 422 | `sale:product-not-active`, `sale:employer-not-active`, `sale:payment-term-not-active`, `sale:structure-not-active` | a deactivated entry |

**Audit:** `SALE_CAPTURED`, target `TRANSACTION` / the sale id, with `clientId`, `consultantId`, `transactionDate`
and `products` (how many lines). Never amounts or client details. The import's `IMPORT_CONFIRMED` entry records
`replacedTypedInSales`.

### GET /api/transactions?clientId=&from=&to=

**Who:** everyone signed in; a CONSULTANT only for their own clients (others are `404 client:not-found`).

| Param | Rules |
|---|---|
| `clientId` | required |
| `from`, `to` | optional dates (`2026-09-14`), both inclusive; `from` after `to` is `400 sale:invalid-date-range` |

**200** - the client's sales, newest first, at most 200, each shaped like the POST answer above. It holds the
typed-in sales (also those an import has since replaced, with `replaced: true`) and the live imported ones; rows a
re-import superseded are left out, as in the reports. Imported sales from before V13 may have `weekStarting` and
`dayOfWeek` null.

### GET /api/transactions?from=&to=&consultantId=&page=&size=

The **Sales history** page. Without `clientId` this is a different list from the one above: every sale in a date
range, a page at a time, with the totals of the whole range.

**Who:** everyone signed in. A CONSULTANT only ever gets the sales recorded against their own consultant record
(a `consultantId` they send is ignored). MANAGER and ADMIN get everyone's and may filter by consultant.

| Param | Rules |
|---|---|
| `from`, `to` | required dates (`2026-09-14`), both inclusive. Missing or not a date: `400`. `from` after `to`: `400 sale:invalid-date-range` |
| `consultantId` | optional, MANAGER / ADMIN only. Unknown id: `404 consultant:not-found` |
| `page`, `size` | default `0` and `25`; `size` at most 100 |

Which sales, and their totals, come from `v_transaction_totals`, **the same as the Reports page**, so a week here
always adds up to the same figures there:

- typed-in and imported sales are both listed (`typedIn` tells them apart);
- a typed-in sale the week's spreadsheet replaced is **left out** (the imported row counts instead), as are rows a
  re-import superseded;
- an imported sale's `quotationTotal` and `bankedTotal` are the spreadsheet's own figures, not the product rule.

**200** - newest first:

```json
{
  "items": [
    {
      "id": 9001, "clientId": 101, "consultantId": 4,
      "transactionDate": "2026-09-15", "weekStarting": "2026-09-14", "dayOfWeek": 2,
      "lines": [ { "productId": 1, "name": "Combo", "amount": 1500.00,
                   "countsTowardQuotation": true, "countsTowardBanked": true } ],
      "quotationTotal": 1500.00, "bankedTotal": 1500.00, "typedIn": true,
      "clientName": "Thandi Mokoena", "consultantName": "Sipho Dlamini"
    }
  ],
  "page": 0, "size": 25, "totalItems": 1, "totalPages": 1,
  "quotationTotal": 1500.00, "bankedTotal": 1500.00
}
```

`quotationTotal` and `bankedTotal` at the top are for the **whole range**, not only this page. `consultantName` is
`null` for an imported sale whose consultant name matched nobody. No ID number, full or masked: the client is shown by
name and linked by `clientId`. Not audited (it shows no sensitive data), like the reports.

## 4. Not built yet

- Changing or deleting a typed-in sale. Until then, a wrong sale is fixed in the spreadsheet, which replaces it
  when the week is imported.
- Capturing does not assign an unassigned client to the consultant, and does not fill in a client's employer.
