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

- **There are no built-in targets** (V25 removed the old R120,000 / R600,000 starting row). Targets only come from a manager or administrator saving them on the Targets page (`POST /api/targets`), and are kept in the `sales_targets` table. The Excel import never creates or changes a target.
- A day's target is the one saved with the latest `effectiveFrom` on or before that date; a week's is the one on or before its Monday. When there is none, `dailyTarget` / `weeklyTarget` and `targetMet` are `null` for scope `ALL` too, and the app shows **"Target not configured"** with no percentage or progress bar. CSV exports write `Not configured` in the Target column.
- `targetMet` is `true` when quotation **>=** target. (The workbook used `>`; agreed to change, Q5.) It is `null` when no target is configured.
- Targets are company targets: `null` for scope `OWN`.

#### Setting targets (MANAGER, ADMIN)

- `GET /api/targets` → `{ today, current, history[] }`. On a fresh database `current` is `null` and `history` is empty. Each entry: `id`, `effectiveFrom`, `dailyTarget`, `weeklyTarget`, `setByEmail`, `createdAt`. `history` is newest first and includes future (scheduled) targets.
- `POST /api/targets` with `{ "effectiveFrom": "2026-11-16", "dailyTarget": 150000, "weeklyTarget": 750000 }` → `200` with the saved entry. Posting the same `effectiveFrom` again replaces that entry.
- The daily target applies to each day from `effectiveFrom`; the weekly target applies to each week whose Monday is on or after `effectiveFrom`.
- A past `effectiveFrom` (before today, Africa/Johannesburg) is allowed only while no target applies to that date, that is before the earliest target ever saved (or any date when none has been saved). This lets a manager give targets to older registers that were imported before targets were set. A past date that already has a target → `422` `urn:credit-advisory:target:past-date`, so a period that had a target never changes. Missing or non-positive amounts → `400`. A consultant → `403`.
- Each save writes a `SALES_TARGET_SET` audit entry.

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

---

## 9. Months and date ranges

Sections 2–7 report one imported week. These endpoints report **any month or date range** instead. Every sale counts on its own `transaction_date`, not on the week it was imported with, so the week of 28 Sept – 4 Oct is split between September and October. Replaced imports (`superseded_by_import_job_id`) are never counted, as in the weekly reports. Who sees what is the same as section 1: `ADMIN`/`MANAGER` get scope `ALL`, a consultant gets `OWN` and no company targets.

### GET /api/reports/months

The months that have sales, newest first.

```json
{
  "scope": "ALL",
  "months": [
    { "month": "2026-10", "from": "2026-10-01", "to": "2026-10-31",
      "transactionCount": 2, "quotationTotal": 250000.00, "bankedTotal": 200000.00 }
  ]
}
```

### The period: `?from=yyyy-mm-dd&to=yyyy-mm-dd`

Both dates are included. At most 366 days. A missing or badly written date, `to` before `from`, or a longer period is `400` with type `urn:credit-advisory:report:invalid-period`. A month is simply its first and last day (the `from` and `to` in the month list).

### GET /api/reports/range/summary

```json
{
  "scope": "ALL", "from": "2026-09-28", "to": "2026-10-04",
  "totals": { "transactionCount": 4, "clientCount": 3, "unattributedCount": 1,
              "quotationTotal": 480000.00, "bankedTotal": 420000.00,
              "daysWithSales": 4, "daysJudged": 4, "daysTargetMet": 2 },
  "days": [ { "date": "2026-09-28", "dayOfWeek": 1, "transactionCount": 1, "clientCount": 1,
              "quotationTotal": 100000.00, "bankedTotal": 90000.00,
              "dailyTarget": 120000.00, "targetMet": false } ],
  "weeks": [ { "weekStarting": "2026-09-28", "from": "2026-09-28", "to": "2026-10-04", "wholeWeek": true,
               "transactionCount": 4, "quotationTotal": 480000.00, "bankedTotal": 420000.00,
               "weeklyTarget": 600000.00, "targetMet": false } ]
}
```

- `days` has every date in the period, including days with no sales.
- `dailyTarget` is the target in force **on that date**; `weeklyTarget` the one in force on that **week's Monday** (section 1, Targets). An old period keeps the targets it had at the time.
- `weeks` has one row per Monday-to-Sunday week the period touches. `wholeWeek` is `false` when the period starts or ends mid-week (a month usually does); then `from`/`to` are the part inside the period and `targetMet` is `null`, because part of a week is never judged against a full week's target.
- `daysJudged` counts the days with sales that had a daily target configured; `daysTargetMet` counts how many of those reached it. With no target for any of them, `daysJudged` is `0` and `daysTargetMet` is `null` ("Target not configured"). Days with no sales are never counted as missed.
- `clientCount` counts each client once over the whole period.
- `OWN`: `unattributedCount`, `daysJudged`, `daysTargetMet`, `dailyTarget`, `weeklyTarget` and `targetMet` are `null`.

### GET /api/reports/range/consultants

One row per consultant with sales, then sales without a consultant (`"consultant": null`) last; otherwise biggest quotation first. Fields: `consultant` (`id`, `consultantCode`, `fullName`), `transactionCount`, `clientCount`, `quotationTotal`, `bankedTotal`. A consultant gets only their own row.

### GET /api/reports/range/products

Optional `&consultantId=` for `ADMIN`/`MANAGER` (`404 consultant:not-found` if there is no such consultant). A consultant always gets their own products, whatever they pass. Rows: `product` (`id`, `code`, `name`), `lineCount`, `totalAmount`, in the catalogue's display order.

### GET /api/reports/range/budget-structures (MANAGER, ADMIN)

Rows: `budgetStructure`, `entryCount`, `loanAmountTotal`, primary and secondary structures together, spaces tidied as in section 6. A consultant gets `403`.

### CSV downloads

`/api/reports/range/summary.csv`, `consultants.csv`, `products.csv`, `budget-structures.csv`, with the same `from`, `to` (and `consultantId`) and the same format as section 7. `summary.csv` has the day rows, then one row per week (`Week` for a whole week, `Part of week` otherwise), then a `Period` total row. File names look like `report-summary-2026-09-01-to-2026-09-30.csv`. Each export is audited as `REPORT_EXPORTED` with `report`, `period` (`2026-09-01-to-2026-09-30`), `scope` and `consultantId`.

### In the app

- **Reports** page: a Week / Day / Month / Date range switch. Week works as before; Day shows one date (`from` = `to`); Month lists the months from `/months`; Date range takes two dates. Imported and typed-in sales are counted the same way, on their own date. Links can open a period directly: `/reports?week=2026-10-05`, `/reports?day=2026-10-07`, `/reports?month=2026-10`, `/reports?from=2026-09-01&to=2026-09-30`, or `/reports?period=month`.
- **Dashboard**: the week card is titled with the week it shows (for example "Week of 5 Oct 2026", marked *Latest import*), has a week picker, says when no register has been imported for the current week yet, labels the target **"Target for that week"** (or **"Target not configured"**), and for managers shows **today's target** beside it, with a note when it has changed since that week.
