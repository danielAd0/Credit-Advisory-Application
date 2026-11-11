# API contract - weekly reports (FR-20)

The figures from the imported weekly registers: the workbook's **Report** and **Breakdown** sheets, and more. Everything here is read-only and calculated by the database (the V15 report views), never copied from the workbook's own Report sheet.

The conventions of `docs/api-auth-consultants-clients.md` section 1 apply: session cookie, ProblemDetail errors with `type` `urn:credit-advisory:<code>`, JSON only.

---

## 1. Rules for every report

### Who sees what

| Role           | `scope` | What they get                                                                                                                                                                               |
| -------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| MANAGER, ADMIN | `ALL`   | the whole company, including sales without a consultant and the company targets                                                                                                             |
| CONSULTANT     | `OWN`   | only the sales attributed to **their own** consultant record. Never another consultant's figures, never the sales without a consultant, never the company targets (those fields are `null`) |

- A consultant-role user whose login is not linked to a consultant record gets the same answers with every figure `0` (and empty lists where the report is a list).
- "Their own" means the consultant written on the sale in the register, not who the client is assigned to today. A sale stays with the consultant who made it after the client is transferred.
- Budget by structure (section 6) is MANAGER / ADMIN only: it cannot be split per consultant.
- Every response is scoped on the server. Hiding a figure in the frontend is not enough, and not needed.

### The week

- `{weekStarting}` in a URL is the **Monday** of the week, written `yyyy-mm-dd`, for example `2026-09-14`.
- A week has reports once a weekly register has been imported **and confirmed** for it (`docs/api-imports.md`), or as soon as one sale has been typed in for it on the Consultant actions page (`docs/api-sales.md`). A week with neither is `404`.
- Importing a week replaces its typed-in sales (the spreadsheet wins), so a sale is never counted twice.
- When a week is imported again (a replacement), the reports show only the newest import. The replaced rows are kept in the database but never counted.

### Days

Every `days` list has exactly **seven** entries, `dayOfWeek` `1` (Monday) to `7` (Sunday), also for days with no sales (their figures are `0`), so the frontend can draw the Monday-to-Sunday grid without filling gaps.

### Money

Amounts are numbers in rand with two decimals (`1900.00`). Quotation and Banked are the values the register itself calculated for each sale; for a sale captured without them they are calculated from the product lines and each product's `countsTowardQuotation` / `countsTowardBanked` flags.

### Targets

- Daily target: R120,000 quotation. Weekly target: R600,000 quotation. They come from the `sales_targets` table, so they can change over time.
- `targetMet` is `true` when quotation **>=** target. (The workbook used `>`; agreed to change, Q5.)
- Targets are company targets: `null` for scope `OWN`.

### Errors (all report endpoints)

| Status | `type`                                      | When                                                                  |
| ------ | ------------------------------------------- | --------------------------------------------------------------------- |
| 400    | `urn:credit-advisory:report:invalid-week`   | `{weekStarting}` is not a date like `2026-09-14`, or not a Monday     |
| 401    | `urn:credit-advisory:auth:unauthenticated`  | not signed in                                                         |
| 403    | `urn:credit-advisory:auth:forbidden`        | budget by structure, called by a CONSULTANT                           |
| 404    | `urn:credit-advisory:report:week-not-found` | no register imported and no sale typed in for that week               |
| 404    | `urn:credit-advisory:consultant:not-found`  | products report, `?consultantId=` of a consultant that does not exist |

Reports contain totals only: no client names, no ID numbers. Viewing them is not audited (like the dashboard); downloading one as a file is (section 7).

---

## 2. GET /api/reports/weeks

**Who:** any signed-in user

**Purpose:** the week picker - every week with an imported register or typed-in sales, newest first, with its totals.

**200 OK**

```json
{
  "scope": "ALL",
  "weeks": [
    {
      "weekStarting": "2026-09-21",
      "transactionCount": 1,
      "quotationTotal": 100.0,
      "bankedTotal": 100.0
    },
    {
      "weekStarting": "2026-09-14",
      "transactionCount": 4,
      "quotationTotal": 133600.0,
      "bankedTotal": 103425.0
    }
  ]
}
```

For scope `OWN` the totals are the consultant's own; a week where they sold nothing is still listed, with zeros.

---

## 3. GET /api/reports/weeks/{weekStarting}/summary

**Who:** any signed-in user

**Purpose:** the workbook's **Report** sheet - quotation and banked per day against the daily target, number of clients, and the week against the weekly target.

**200 OK** (scope `ALL`; only Monday, Tuesday and Sunday shown here - there are always seven)

```json
{
  "scope": "ALL",
  "weekStarting": "2026-09-14",
  "weekEnding": "2026-09-20",
  "days": [
    {
      "dayOfWeek": 1,
      "date": "2026-09-14",
      "transactionCount": 2,
      "clientCount": 2,
      "quotationTotal": 3100.0,
      "bankedTotal": 2925.0,
      "dailyTarget": 120000.0,
      "targetMet": false
    },
    {
      "dayOfWeek": 2,
      "date": "2026-09-15",
      "transactionCount": 1,
      "clientCount": 1,
      "quotationTotal": 130000.0,
      "bankedTotal": 100000.0,
      "dailyTarget": 120000.0,
      "targetMet": true
    },
    {
      "dayOfWeek": 7,
      "date": "2026-09-20",
      "transactionCount": 0,
      "clientCount": 0,
      "quotationTotal": 0.0,
      "bankedTotal": 0.0,
      "dailyTarget": 120000.0,
      "targetMet": false
    }
  ],
  "week": {
    "transactionCount": 4,
    "clientCount": 3,
    "unattributedCount": 1,
    "quotationTotal": 133600.0,
    "bankedTotal": 103425.0,
    "weeklyTarget": 600000.0,
    "targetMet": false
  }
}
```

| Field                                      | Meaning                                                                                                                  |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| `transactionCount`                         | rows in the register (sales)                                                                                             |
| `clientCount`                              | **different** clients. In `week` a client who bought on two days counts once, so it can be less than the sum of the days |
| `week.unattributedCount`                   | sales with no consultant in the register. `null` for scope `OWN`                                                         |
| `dailyTarget`, `weeklyTarget`, `targetMet` | `null` for scope `OWN`                                                                                                   |

---

## 4. GET /api/reports/weeks/{weekStarting}/consultants

**Who:** any signed-in user

**Purpose:** consultant performance - quotation and banked per consultant per day, and their week.

**200 OK** (scope `ALL`; `days` shortened here - always seven)

```json
{
  "scope": "ALL",
  "weekStarting": "2026-09-14",
  "consultants": [
    {
      "consultant": {
        "id": 4,
        "consultantCode": "C001",
        "fullName": "Thandi Xundu"
      },
      "days": [
        {
          "dayOfWeek": 1,
          "transactionCount": 1,
          "quotationTotal": 1900.0,
          "bankedTotal": 1725.0
        },
        {
          "dayOfWeek": 2,
          "transactionCount": 0,
          "quotationTotal": 0.0,
          "bankedTotal": 0.0
        }
      ],
      "week": {
        "transactionCount": 2,
        "clientCount": 1,
        "quotationTotal": 2400.0,
        "bankedTotal": 2225.0
      }
    },
    {
      "consultant": null,
      "days": [
        {
          "dayOfWeek": 2,
          "transactionCount": 1,
          "quotationTotal": 130000.0,
          "bankedTotal": 100000.0
        }
      ],
      "week": {
        "transactionCount": 1,
        "clientCount": 1,
        "quotationTotal": 130000.0,
        "bankedTotal": 100000.0
      }
    }
  ]
}
```

- Only consultants with at least one sale that week are listed, ordered by consultant code.
- The row with `"consultant": null` is the sales without a consultant. It is always last, and only for scope `ALL`. Show it as "No consultant".
- Scope `OWN`: only the consultant's own row, or an empty `consultants` list if they sold nothing that week.

---

## 5. GET /api/reports/weeks/{weekStarting}/products

**Who:** any signed-in user

**Purpose:** the workbook's **Breakdown** sheet - each product's amount per day.

**Query parameters**

| Param          | Who            | Meaning                                                              |
| -------------- | -------------- | -------------------------------------------------------------------- |
| `consultantId` | MANAGER, ADMIN | only that consultant's sales. Omitted = everyone. Unknown id = `404` |
| `consultantId` | CONSULTANT     | ignored: a consultant always gets their own figures                  |

**200 OK** (`days` shortened here - always seven)

```json
{
  "scope": "ALL",
  "weekStarting": "2026-09-14",
  "consultantId": null,
  "products": [
    {
      "product": { "id": 1, "code": "COMBO", "name": "Combo" },
      "days": [
        { "dayOfWeek": 1, "lineCount": 2, "totalAmount": 2700.0 },
        { "dayOfWeek": 2, "lineCount": 1, "totalAmount": 130000.0 }
      ],
      "week": { "lineCount": 3, "totalAmount": 132700.0 }
    },
    {
      "product": {
        "id": 5,
        "code": "MAFORI_VOUCHER",
        "name": "Mafori Voucher"
      },
      "days": [{ "dayOfWeek": 1, "lineCount": 0, "totalAmount": 0.0 }],
      "week": { "lineCount": 0, "totalAmount": 0.0 }
    }
  ]
}
```

- Every **active** product is listed, also with zero sales, in the catalogue's display order. An inactive product is listed only in a week it was sold.
- `lineCount` = how many sales included the product.
- `consultantId` in the response says whose figures these are: the filter used, the consultant's own id for scope `OWN`, or `null` for everyone.
- Product amounts are **not** the same as quotation or banked: some products count toward one and not the other. Do not add product totals up and call it quotation.

---

## 6. GET /api/reports/weeks/{weekStarting}/budget-structures

**Who:** MANAGER, ADMIN (a CONSULTANT gets `403`)

**Purpose:** loan amounts per budget structure. The register has a primary and a secondary structure per sale; each loan amount is added under the structure its budget list names.

**200 OK** (`days` shortened here - always seven)

```json
{
  "weekStarting": "2026-09-14",
  "structures": [
    {
      "budgetStructure": "Structure A",
      "days": [
        { "dayOfWeek": 1, "entryCount": 1, "loanAmountTotal": 5000.0 },
        { "dayOfWeek": 2, "entryCount": 1, "loanAmountTotal": 2000.0 }
      ],
      "week": { "entryCount": 2, "loanAmountTotal": 7000.0 }
    }
  ]
}
```

- Ordered alphabetically. Spaces in a structure name are tidied first, so "Structure A" and "Structure A" are one structure.
- Only structures that appear in that week are listed.

---

## 7. Exports (FR-22) - CSV downloads

The same four reports as files that Excel opens with a double-click. Same scoping, same `{weekStarting}` rules and the same errors as the JSON reports (errors are still JSON ProblemDetail, not a file).

| Endpoint | Who | The file (one line per...) |
|---|---|---|
| `GET /api/reports/weeks/{weekStarting}/summary.csv` | any signed-in user | day (Monday to Sunday), then a `Week` line |
| `GET /api/reports/weeks/{weekStarting}/consultants.csv` | any signed-in user | consultant; `No consultant` last (scope `ALL` only) |
| `GET /api/reports/weeks/{weekStarting}/products.csv` | any signed-in user | product. `?consultantId=` works exactly like section 5 |
| `GET /api/reports/weeks/{weekStarting}/budget-structures.csv` | MANAGER, ADMIN | budget structure |

**Response**

```
200 OK
Content-Type: text/csv;charset=UTF-8
Content-Disposition: attachment; filename="report-summary-2026-09-14.csv"

Day,Date,Sales,Clients,Quotation,Banked,Target,Target met
Monday,2026-09-14,2,2,3100.00,2925.00,120000.00,No
Tuesday,2026-09-15,1,1,130000.00,100000.00,120000.00,Yes
...
Sunday,2026-09-20,0,0,0.00,0.00,120000.00,No
Week,2026-09-14 to 2026-09-20,4,3,133600.00,103425.00,600000.00,No
```

**Columns**

| File | Columns |
|---|---|
| `summary.csv` | `Day`, `Date`, `Sales`, `Clients`, `Quotation`, `Banked`, `Target`, `Target met`. For scope `OWN` the last two are empty |
| `consultants.csv` | `Consultant code`, `Consultant`, then `Monday quotation`, `Monday banked` ... `Sunday quotation`, `Sunday banked`, then `Week sales`, `Week clients`, `Week quotation`, `Week banked` |
| `products.csv` | `Product code`, `Product`, `Monday` ... `Sunday`, `Week sales`, `Week total` |
| `budget-structures.csv` | `Budget structure`, `Monday` ... `Sunday`, `Week entries`, `Week loan total` |

**File names:** `report-<report>-<weekStarting>.csv`, for example `report-consultants-2026-09-14.csv`. A products export for one consultant ends in `-consultant-<id>.csv` (also a consultant's own export).

**Format**

- UTF-8 with a byte-order mark (so Excel shows names with accents correctly), comma-separated, lines end with CRLF.
- Amounts are plain numbers with two decimals (`1900.00`), no currency sign and no thousands separator, so Excel can add them up. `Target met` is `Yes` / `No`.
- **Formula protection:** any text that starts with `=`, `+`, `-` or `@` gets a `'` in front (Excel then shows it as text instead of running it). Names and structures come from the register, so this stops a planted formula from running on a manager's computer.
- Text with a comma, quote or line break is in double quotes, with quotes doubled.
- No client names, no ID numbers - totals only, like the JSON reports.

**Audit:** every successful export writes `REPORT_EXPORTED` with `report` (`summary`, `consultants`, `products`, `budget-structures`), `weekStarting`, `scope` and `consultantId` (products only). A refused export (`400`, `403`, `404`) writes nothing. Viewing the JSON reports is not audited.

**Frontend:** a plain link works, because the session cookie is sent with it: `<a href="/api/reports/weeks/2026-09-14/summary.csv">Download</a>`. No CSRF token is needed (it is a GET).

---

## 8. Not built yet

- ~~Reconciliation with the workbook's own Report sheet~~: built in stage 4 of the import. See `GET /api/imports/{id}/reconciliation` and `/report-figures` in `docs/api-imports.md`, section 8.
