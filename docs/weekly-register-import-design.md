# Weekly register import: mapping and design

**Status:** stages 1–3 built (migrations V13–V16, fake test workbook, reader, parser, preview/confirm API in `docs/api-imports.md`). Stages 4–6 follow this design.
**Owner:** Sifiso (import, migrations, data integrity). **Frontend:** Jack. **Client and identity code reused:** Daniel.
**Source analysed:** *Credit Advisory Online Dashboard (14 – 18 Sept 2026)*. Only the structure was analysed;
no client values are copied into this document or the code.

---

## 0. Decisions already made

| # | Question | Decision |
| --- | --- | --- |
| D1 | Which figures are the truth when Report or Breakdown disagree with the client rows? | **Recalculate from the client rows and also store the sheet's figures.** Every difference is listed in the import summary for the manager to check. |
| D2 | Rows with no consultant | **Import them.** The client stays unassigned, the row is flagged, and it shows up in the "assign clients" list. |
| D3 | Quotation / Banked per row | **Store the row's own values** exactly as the sheet calculated them, plus every product amount. Flag rows whose day uses a different formula. |
| D4 | Process | This document first, then build in stages (section 13). |
| D5 | Rows with **only an ID number** and nothing else (the real file has 43 on Tuesday) | **Rejected** (`row:missing-name`), so the manager cleans up the sheet. They are not imported and not counted as clients (decided 5 Nov 2026). |

---

## 1. What the workbook contains

| Sheet | Role | Import? |
| --- | --- | --- |
| `Monday`, `Tuesday`, `Wednsday` (sic), `Thursday`, `Friday`, `Saturday`, `Sunday` | One row per client transaction for that day | **Yes**: the source of truth |
| `Report` | Daily banked, quotation, target and client count; per-consultant product tables; finance (VAT); budget by structure | **Yes**: stored as *sheet figures* and reconciled |
| `Breakdown` | Consultant × day tables per product, plus a summary | **Yes**: stored as *sheet figures* and reconciled |
| `Dashboard` | Empty (only the word "Dashboard") | No. Reported as "no data" |
| `Sheet1`–`Sheet10` | Hidden, empty | Ignored. Error if any has data |

The analysed week had 110 / 101 / 57 / 43 / 50 rows on Mon–Fri, and Saturday and Sunday were empty. Each day sheet
also carries **12,000–19,700 formatted rows with formulas but no data**, so the parser must skip those quickly.

---

## 2. Day sheets: column mapping

Columns are found **by header text in row 1, never by letter**, because every day uses a different layout.
Headers are matched after trimming, upper-casing and collapsing spaces, and through the alias list in section 3.

### 2.1 Where each header sits in the analysed workbook

| Header in sheet | Mon | Tue | Wed | Thu | Fri | Sat | Sun | Stored in |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DATE | A | A | A | A | A | A | A | `client_transactions.transaction_date` (see 4.3) |
| NAME | B | B | B | B | B | B | B | `clients.first_name` |
| SURNAME | C | C | C | C | C | C | C | `clients.last_name` |
| ID NUMBER | D | D | D | D | D | D | D | `clients.id_number_hash / _encrypted / _last4` via `IdNumberProtector` |
| QUOTATION | E | E | E | E | E | E | E | `client_transactions.quotation_amount` **(new, D3)** |
| COMBO | F | F | F | F | F | F | F | line → product `COMBO` |
| IVC | G | G | G | G | G | G | G | line → `IVC` |
| INSURANCE | H,Q | H,Q | H | H | H | — | — | line → `INSURANCE` (two columns added together, see 4.6) |
| MAFORI CARD | I | I | I | I | I | — | — | line → `MAFORI_CARD` |
| MAFORI VOUCHER | J | J | J | J | J | H | H | line → `MAFORI_VOUCHER` |
| CREDIT RESCUE | K | K | K | K | K | I | I | line → `CREDIT_RESCUE` |
| CREDIT REPAIR | L | L | L | L | L | J | J | line → `CREDIT_REPAIR` |
| CREDIT REPAIR DEBIT ORDER | M | M | M | M | M | K | K | line → `CREDIT_REPAIR_DEBIT_ORDER` |
| SPEED SERVICE | N | N | N | N | N | L | L | line → `SPEED_SERVICE` |
| CASH ADVANCE LITE | O | O | O | O | O | O | — | line → `CASH_ADVANCE_LITE` |
| GROCERY / GROECRY | P | P | P | P | P | N | N | line → `GROCERY` |
| HOUSING ADMIN | R | R | Q | — | Q | P | O | line → `HOUSING_ADMIN` |
| MAFORI WELLNESS | S | S | R | Q | R | Q | — | line → `MAFORI_WELLNESS` |
| VOLUTARY DISTRIBUTION (sic) | T | T | S | R | S | — | — | line → `VOLUNTARY_DISTRIBUTION` |
| POLICY REPLACEMENT | — | — | — | — | — | — | P | line → `POLICY_REPLACEMENT` |
| AMOUNT TO CLIENT | U | U | T | S | T | R | Q | `client_transactions.amount_to_client` |
| *(no header)* | V | V | — | — | — | M | M | `client_transaction_extras` with a warning if it holds data (4.7) |
| 3RD PARTY SETTLEMENT | W | W | U | T | U | S | R | `client_transactions.third_party_settlement` |
| BANKED | X | X | V | U | V | T | S | `client_transactions.banked_amount` **(new, D3)** |
| PAYMENT TERM | Y | Y | W | V | W | U | T | `payment_term_id` (matched by name) |
| EMPLOYER | Z | Z | X | W | X | V | U | `employer_id` (matched by name or alias) and `clients.employer_id` |
| STRUCTURE | AA | AA | Y | X | Y | W | V | `structure_id` |
| CONSULTANT | AB | AB | Z | Y | Z | X | W | `consultant_id` (matched by name or alias, see 4.4) |
| PRIMARY STRUCTURE BUDGET LIST | AC | AC | AA | Z | AA | Y | X | `primary_structure_budget_list` |
| PRIMARY STRUCTURE LOAN AMOUNT | AD | AD | AB | AA | AB | Z | Y | `primary_structure_loan_amount` |
| SECONDARY STRUCTURE BUDGET LIST | AE | AE | AC | AB | AC | AA | Z | `secondary_structure_budget_list` |
| SECONDARY STRUCTURE LOAN AMOUNT | AF | AF | AD | AC | AD | AB | AA | `secondary_structure_loan_amount` |

**Any header not in this table is not dropped** (requirement 5). If it holds numbers in data rows, it is stored in
`client_transaction_extras` and the preview warns "unknown column X on Tuesday – stored as an extra field; ask an
admin to map it to a product". If the column is empty in every data row, the preview just reports it.

**Required headers:** NAME, SURNAME, ID NUMBER, CONSULTANT, BANKED, QUOTATION. A day sheet missing one of these
fails the preview (`sheet:missing-header`). A day sheet that has no data rows at all, like Saturday and Sunday in
the analysed file, is fine and reported as "0 rows".

### 2.2 How each day calculated Quotation and Banked (decoded from row 2)

| Day | QUOTATION = | BANKED = |
| --- | --- | --- |
| Mon, Tue | Combo…Speed Service + Housing + Insurance(2nd) + Wellness + Vol. Distribution | Combo + IVC + Voucher + Rescue + Speed + Insurance ×2 + Housing + Wellness + Vol. Dist + **Card** |
| Wed | Combo…Speed Service + Housing + **Cash Adv. Lite** + Vol. Dist + Wellness | as Mon + Cash Adv. Lite |
| Thu | Combo…Speed Service + Wellness + Cash Adv. Lite + **Amount to Client** + Vol. Dist | as Wed, + **Amount to Client** |
| Fri | Combo…**Grocery** + Vol. Dist + Housing + **3rd Party Settlement + Amount to Client** | as Wed, **without Mafori Card** |
| Sat | Combo…Speed Service + Housing + Cash Adv. Lite + 3rd Party + Wellness | Combo + IVC + Voucher + Rescue + Speed + Cash Adv. + Housing |
| Sun | *(no formula)* | Combo + IVC + Voucher + Rescue + Speed + *(blank col)* + Housing + Policy Repl. |

Credit Repair and Credit Repair Debit Order are **never** in Banked. Every day's rule is different, so per D3 the
import keeps each row's cached Quotation and Banked values. It also recalculates them with the **standard rule**
(product flags `counts_toward_quotation` / `counts_toward_banked`) and stores a `formula_mismatch` warning per day
when they differ. The business should agree one rule (Q2).

---

## 3. Header and value normalisation

| Kind | Rule |
| --- | --- |
| Header text | Trim, upper-case, collapse spaces; strip trailing spaces (`'ID NUMBER '`) |
| Header aliases (seeded table `import_header_aliases`) | `GROECRY`→GROCERY · `VOLUTARY DISTRIBUTION`→VOLUNTARY_DISTRIBUTION · `GROCERIES`→GROCERY · `ID NUMBER`/`ID NO`/`IDNUMBER`→ID NUMBER · `3RD PARTY SETTLEMENT`/`THIRD PARTY SETTLEMENT` |
| Sheet names | Matched to weekdays by prefix and alias: `Wednsday`→WEDNESDAY, `Mon`→MONDAY, … An unrecognised visible sheet that has data gives a warning |
| Names | Trim and collapse spaces (the sheet has leading and trailing spaces) |
| Money | Numeric cells (cached formula results included) to 2 decimals. Text such as "R1 500,50" or "1,500.00" is parsed with a warning; "-" means 0; anything else gives `row:invalid-amount`. Negative gives a row error. Blank and 0 mean no line |
| Formula cells | **Never evaluated.** The cached value is read. An error result (`#REF!`, `#VALUE!`) in a product, QUOTATION or BANKED is a row error; in AMOUNT TO CLIENT, 3RD PARTY SETTLEMENT or the loan amounts it is a warning and treated as empty (the real Saturday/Sunday loan columns hold broken formulas that give `#VALUE!` on every client row) |
| Text categories (payment term, structure, employer, consultant) | Trim, collapse spaces, case-insensitive match |

---

## 4. Row rules

### 4.1 What counts as a data row
A row is a data row if **any of NAME, SURNAME or ID NUMBER has a value**. Formula-only rows are skipped silently.
The workbook is read sparsely (only non-empty cells), so the ~20k formatted rows cost nothing and data further
down is never missed. The header row is the first of the first 10 rows that has NAME, SURNAME and ID NUMBER.

### 4.2 ID number
- Read as text. In the analysed file **~80% of IDs are numbers and ~20% are text** (295 vs 66), often on the same sheet.
- A number is converted without decimals or exponent. **A 12-digit number gets one leading zero added back**,
  because Excel drops it for people born in 2000–2009. That fix is noted as a warning on the row.
- Validated with `SaIdNumber.parse` (Daniel's code). Invalid or missing gives `row:invalid-id-number` and the row is
  **rejected** (SRS 6.2). The message gives the reason, never the number.
- Duplicates within the upload are **allowed**: the same person can appear on two days, or twice in a day. Three
  IDs appear twice in the analysed week. This is one client with several transactions, shown as an info line.

### 4.3 Transaction date
The DATE column is **empty in every row** of the analysed file. The manager picks the **week (a Monday)** when
uploading; it is prefilled from a file name like "14 - 18 Sept 2026". Each row's date is then *week Monday + the
sheet's weekday*. If a DATE cell has a value, it must equal that date, otherwise `row:date-outside-day` (error).

### 4.4 Consultant
- Matched against `consultants.full_name` (case- and space-insensitive), then against `consultant_aliases`. The
  sheet uses first names and labels such as "AMBITIOUS", "VICTORIA" and "ABIGAIL", so **no guessing by partial name**.
- **Blank**: the row is imported with `consultant_id = NULL` and a warning (D2).
- **Unknown name**: confirm is blocked until a manager maps the name in the preview, either to an existing
  consultant (which saves an alias for every future week) or by creating a new consultant. Then it is re-checked.
- A consultant who is INACTIVE or SUSPENDED can still be *attributed* to a past transaction, but a **new client is
  not assigned** to them (FR-17). That gives a warning.

### 4.5 Client create or update (per ID)
| Situation | Action |
| --- | --- |
| ID not in the database | Create the client. Assign it to the row's consultant if that consultant is ACTIVE (`IMPORT` history row with the import job id), otherwise leave it unassigned |
| ID exists, same names | Use it. No change |
| ID exists, different names | Keep the stored names. Warning `client:name-differs` (names are never silently overwritten) |
| ID exists, assigned to another consultant | **Don't reassign** (SRS 6.2: never undo a manager's transfer). The transaction still records the row's consultant. Warning `client:consultant-differs` |
| ID exists, unassigned, row has an ACTIVE consultant | Assign (`IMPORT` history). Info line |
| Employer | `clients.employer_id` is set when empty. If it differs, the client keeps the old one and the transaction records the row's employer |

### 4.6 Product lines
One `client_transaction_lines` row per product with an amount above 0. The two INSURANCE columns on Mon/Tue are
**added together** into one INSURANCE line, with a warning if both are non-zero. Q3 asks whether the second
column is a different product. Each line copies the product's quotation and banked flags as they are at import time.

### 4.7 Extra columns
`client_transaction_extras (transaction_id, sheet_header, value_text, value_number)` keeps every non-empty cell
of an unmapped column, including headerless ones (labelled "Column V").

### 4.8 Lookups that create records
| Value | Not found → |
| --- | --- |
| Payment term ("ONCE OFF PAYMENT") | Matched to `payment_terms.name`, ignoring case and the word "PAYMENT" (`Once off payment`). Unknown creates a new term (`code` from the text) with a warning |
| Structure ("VITAL CREDIT") | Created if missing, with a warning |
| Employer | Exact normalised name, then `employer_aliases` (new). Unknown creates an employer with `needs_review = TRUE` and a warning. **The analysed week has 119 distinct employer spellings; only some match the 63 from the Employer Schedule** (e.g. "PHUMELELA LOCAL" vs "PHUMELELA LOCAL MUNICIPALITY VREDE"). An admin can merge them later, which saves an alias |

### 4.9 Duplicate prevention
1. **Same file**: an import whose `file_digest` (SHA-256) matches a committed job is refused (`import:duplicate-file`).
2. **Same week**: one *committed* weekly import per week. Re-importing a committed week is blocked unless an ADMIN
   chooses **Replace week** with a reason. The old import's transactions are then marked `superseded_by_import_job_id`
   (kept, not deleted, and left out of reports). Q4 confirms this policy.
3. **Same transaction**: inside one import every (sheet, row) is unique (existing constraint).

---

## 5. Report sheet: what is read and how it is checked

Blocks are found **by their titles and header rows, not by cell addresses**: "WEEKLY REPORT" + "BANKED",
"Groceries", "Mafori Voucher", "WEEKLY REPORT (NUMBER OF CLIENTS)", "Housing", "FINANCE", "Budget". Every
value is stored in `weekly_report_figures` (section 7.2) with its cell reference, its sheet value or error text,
the **recalculated** value and the difference.

| Block (section code) | Sheet logic | Recalculated as | Defects found in the analysed file |
| --- | --- | --- | --- |
| Daily banked (`DAILY_BANKED`) | SUM of each day's BANKED column | Sum of `banked_amount` per day | **Sunday sums Saturday's column** |
| Daily quotation (`DAILY_QUOTATION`) | SUM of each day's QUOTATION | Sum of `quotation_amount` per day | – |
| Daily target (`DAILY_TARGET`) | "Target Reached" if quotation **>** 120,000; weekly > 600,000 | The sheet's own threshold, with the rule **≥** (the app's reports use `sales_targets`) | Uses > not ≥ (Q5): flagged `THRESHOLD_GREATER_THAN` on every target cell |
| Clients per day (`DAILY_CLIENT_COUNT`) | `COUNT(ID column)` | Data rows per day, plus distinct IDs per day | **COUNT skips text IDs: Tue 78 vs 101 rows, week 295 vs 361** |
| Groceries per consultant per day (`CONSULTANT_PRODUCT`, GROCERY) | SUMIF by consultant | Lines of GROCERY by consultant and day | **Thursday matches on columns Y:Z** (two columns) |
| Mafori Voucher per consultant per day | SUMIF | Lines of MAFORI_VOUCHER | – |
| Housing per consultant per day | SUMIF | Lines of HOUSING_ADMIN | **Mon sums GROCERY, matched on PAYMENT TERM (not consultant) for 10 of 11 rows; Thu sums MAFORI WELLNESS; Sun sums POLICY REPLACEMENT** |
| Clients per consultant (`CONSULTANT_CLIENT_COUNT`) | COUNTIF consultant | Rows by consultant and day | Saturday counts one consultant from the wrong column. **Blank-consultant rows are excluded (317 vs 361)** |
| Quotation per consultant (`CONSULTANT_QUOTATION`) | SUMIF | Sum of `quotation_amount` by consultant and day | Same exclusion |
| Finance: quotations with VAT (`FINANCE`) | `SUM(E3:E6)+SUM(E8:E9)`; VAT = D156 × 15% | Week quotation, VAT at 15% | **Leaves out Friday (E7)**; VAT is calculated from an empty cell, so it is 0 |
| Difference (`SHEET_DIFFERENCE`) | Consultant totals − daily totals | Same, from rows | The sheet's own check already shows −2,475 and 22 |
| Budget by structure (`BUDGET_BY_STRUCTURE`) | SUMIF of budget-list column = structure name; loan amounts | Loan amounts grouped by budget-list value | **Matches on the wrong columns on most days; Saturday sums GROECRY; Sunday uses the consultant column; one `#VALUE!`; the weekly TOTAL only adds 8 of 20 rows.** The 20 structure names (Advisory Referr, Cash Advance PA, …) are read as the list of budget structures |

## 6. Breakdown sheet

Each block (a title row, then "CONSULTANTS | MONDAY … SUNDAY | TOTAL", then one row per consultant) is stored cell
by cell in `weekly_report_figures` (section `BREAKDOWN`, with the sheet's **block title** and the **product it
actually sums**), alongside the correct recalculation.

| Title in sheet | Actually sums | Defects |
| --- | --- | --- |
| COMBO | COMBO | Rows without a consultant excluded (357,675 vs 359,750) |
| CARD | **IVC** | Title says Card, sums IVC (Q6: labelled IVC until the business answers). Monday column looks up the consultant 3 rows up |
| ADVISORY | **MAFORI VOUCHER** | Title mismatch (Q6). Monday offset |
| CREDIT RESCUE | CREDIT RESCUE | **Monday offset of 9 rows: 7 of 11 consultant totals wrong (R62,675 credited to the wrong people)** |
| CREDIT REPAIR | CREDIT REPAIR | Monday offset |
| SPEED SERVICE | SPEED SERVICE | Monday offset; missing from the summary table |
| GROCERIES | GROCERY (Mon: **CASH ADVANCE LITE**) | Monday sums the wrong column |
| POLICY REPLACEMENT | Mon GROCERY, Tue INSURANCE, Sat CASH ADV., Sun POLICY REPL.; Wed–Fri `#REF!` | **#REF! in 44 cells and the summary** |
| Summary (L:M) | Block totals | "COMB0" (zero, not O); no Speed Service |

The app's **Breakdown page** shows the recalculated table for **every product** (not just the 8 blocks), so
nothing is limited to selected columns. Where the sheet had a block for that product, it also shows the sheet's
value and the difference.

---

## 7. Database changes (built in stage 1)

V12 is Daniel's credit cases migration, so the import migrations are **V13–V15**. All three were run on PostgreSQL
16 after V1–V12 and tested with fake data (section 13, stage 1).

### 7.1 `V13__weekly_register_import.sql`
| Change | Purpose |
| --- | --- |
| `reporting_weeks` (`week_starting` unique, must be a Monday; `current_import_job_id`) | One row per week. History is kept, never overwritten |
| `import_jobs` + `reporting_week_id`, `replaces_import_job_id`, `replace_reason` (required when replacing), `warning_count`, `reconciliation_status` (NOT_CHECKED / MATCHED / DIFFERENCES), `reconciliation_differences` | Week link, "Replace week" (4.9), summary counts. A committed weekly import must have a week |
| `client_transactions`: `consultant_id` now optional (D2); + `reporting_week_id`, `day_of_week` (must equal the date's weekday), `quotation_amount`, `banked_amount` (D3, ≥ 0), `source_consultant_name`, `source_employer_name`, `superseded_by_import_job_id` | Per-row sheet values; replaced imports are kept but excluded |
| Trigger `trg_transactions_in_week` | A transaction's date must fall inside its week |
| `client_transaction_extras` (one per transaction + header; refuses any 13-digit number) | Unrecognised columns (4.7) |
| `employers.needs_review` + `employer_aliases` | Employers created from unknown spellings, and admin merges (4.8) |
| `import_header_aliases` (kind PRODUCT / FIELD / SHEET, seeded) | Header spellings (`GROECRY`, `VOLUTARY DISTRIBUTION`, `ID NUMBER`) and sheet names (`Wednsday`). Data, not code |
| `sales_targets.weekly_quotation_target` (600,000) | Weekly target (section 5) |
| `import_rows` + `day_of_week`, `extras` (JSON), `warning_count`; `import_errors.severity` adds `INFO` | Staging and the summary |

### 7.2 `V14__weekly_report_figures.sql`: the sheet's own figures (D1)
`weekly_report_figures`: one row per Report or Breakdown cell. It holds the section, the block title and labels
as written, the day, consultant, product (what the formula *actually* sums) and structure, the cell reference,
**exactly one of** value / text / error (`#REF!`), the formula, the recalculated value or text, the difference,
`matches`, and a `defect_code` (`WRONG_COLUMN`, `WRONG_CRITERIA_COLUMN`, `ROW_OFFSET`, `WRONG_SHEET`,
`TEXT_ID_NOT_COUNTED`, `RANGE_INCOMPLETE`, `REF_ERROR`, `VALUE_ERROR`, `BLANK_CONSULTANT_EXCLUDED`,
`THRESHOLD_GREATER_THAN`, `UNEXPLAINED`). The table is append-only for updates: the figures are evidence of what
the workbook said.

### 7.3 `V15__weekly_report_views.sql`: recalculated reports (what the app shows)
| View | Columns | Feeds |
| --- | --- | --- |
| `v_week_daily_summary` | week, day 1–7 (**all 7 days, zeros included**), date, transactions, distinct clients, unattributed rows, quotation, banked, daily target, target met (≥) | Banked & quotation report, daily targets, client counts |
| `v_week_totals` | week, transactions, distinct clients, consultants, unattributed, quotation, banked, weekly target, met | Weekly totals and summary |
| `v_week_product_daily` | week, day, consultant (NULL = none), product, line count, amount | Product performance, Breakdown page |
| `v_week_consultant_daily` | week, day, consultant, transactions, clients, quotation, banked | Consultant financial performance (Q1: both) |
| `v_week_budget_by_structure` | week, day, budget structure, entries, loan total (primary + secondary) | Budget report |
| `v_transaction_totals`, `v_daily_sales`, `v_consultant_product_daily` (V6, replaced) | Same columns as before; **use the row's own Quotation / Banked when present**; skip superseded rows; `v_transaction_totals` adds `quotation_by_rule` / `banked_by_rule` for the D3 comparison | Existing queries keep working |

## 8. Import flow and API

> Built in stage 3. The exact, current contract is **`docs/api-imports.md`**; the table below is the original plan.

```
Upload (.xlsx, ≤10 MB, MANAGER/ADMIN) ─► Preview job (validate, stage rows, nothing written to client tables)
     ─► Manager maps unknown consultants / reviews warnings & reconciliation
     ─► Confirm (one transaction: clients, transactions, lines, extras, history, report figures)
     ─► Summary (FR-12) ─► personal data in import_rows purged
```

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/imports/weekly-register/preview` (multipart: `file`, `weekStarting`) | Parse and validate; returns job id and summary |
| GET | `/api/imports` | Import history (week, who, when, state, counts) |
| GET | `/api/imports/{id}` | Summary (section 9) |
| GET | `/api/imports/{id}/rows?sheet=&action=&page=` | Staged rows (masked ID, names, consultant, totals, issues) |
| GET | `/api/imports/{id}/issues?severity=` | Errors and warnings (never a full ID) |
| GET | `/api/imports/{id}/reconciliation?sheet=&onlyDifferences=` | Sheet vs recalculated, cell by cell |
| GET | `/api/imports/{id}/unmapped-consultants` | Names that need mapping |
| POST | `/api/imports/{id}/consultant-mappings` `{ sheetName, consultantId }` | Saves an alias and re-validates |
| POST | `/api/imports/{id}/confirm` `{ fileDigest, replaceReason? }` | Commit (409 if stale, unmapped names or rejected rows unacknowledged) |
| POST | `/api/imports/{id}/cancel` | Cancel and purge |
| GET | `/api/reports/weeks` | Imported weeks |
| GET | `/api/reports/weeks/{monday}/summary` | Daily and weekly banked, quotation, targets, client counts |
| GET | `/api/reports/weeks/{monday}/products` | Product × day, weekly totals, grand total |
| GET | `/api/reports/weeks/{monday}/consultants` | Consultant × day: clients, quotation, banked, products; overall total |
| GET | `/api/reports/weeks/{monday}/breakdown` | Every product block: recalculated, sheet value, difference |
| GET | `/api/reports/weeks/{monday}/budget` | Budget by structure |

A CONSULTANT calling `/api/reports/...` only sees their own rows (same scoping as clients). Everything is audited
(`IMPORT_PREVIEWED`, `IMPORT_CONFIRMED`, `IMPORT_CANCELLED`, `CONSULTANT_ALIAS_CREATED`, `WEEK_REPLACED`).

## 9. Import summary (requirement 8)

Week (Mon–Sun) · client rows imported / rejected · new and existing clients · transactions · product lines ·
consultants included (and unassigned rows) · per-product weekly totals · per-consultant quotation and banked,
and the overall total · daily and weekly banked and quotation · daily target vs actual · clients per day and week
(rows and distinct people) · Breakdown cells imported · **reconciliation: N cells match, M differ (list), K sheet
errors** · warnings and errors by code.

## 10. Reconciliation rules (built in stage 4: `imports/report`)
- **When:** at preview, in the preview's transaction. Every formula cell (and error cell) of Report and Breakdown
  becomes a row of `weekly_report_figures` for that import job. The week's `reporting_weeks` row is created at
  preview for this; V17 keeps weeks without a committed import out of `v_week_daily_summary` / `v_week_totals`.
- **Finding the layout, by labels only** (`ReportReconciler`):
  - *Day summary*: a column with at least 3 day names down the rows; the figures are the next column to the right;
    the header above says what they are (BANK… → banked, QUOT… → quotation, …TARGET… → target text, …CLIENT… →
    number of rows). A "TOTAL" row under the days is the week.
  - *Blocks*: a header row "label | day … | TOTAL …" (any day spelling the `SHEET` aliases know, and headers such
    as "TOTAL Budget Saturday"). The block's meaning comes from its title above the header: a product
    (`import_header_aliases` / product names), "…CLIENTS…" (count), "…QUOTATION…", "Budget" (loan amounts by budget
    list). A header with no title of its own continues the block above (Report's Sunday part). Two halves side
    by side with the same title are one block. A title that is not a product ("CARD", "ADVISORY") takes the
    product most of its formulas add up (Q6). Row labels are consultants (or budget lists); a "TOTAL…" label is a
    column total.
  - *Everything else* (Finance, Difference, per-consultant totals, the Breakdown summary) gets its meaning from
    the cells it adds up, or from a product name as its row label ("CREDIT RESCUE | =SUM(I12:I22)").
- **Recalculated value:** from **every** parsed row, rejected ones included, so a difference comes from the
  workbook's formulas and not from the import's validation. Totals are recalculated from the rows too (not from
  the sheet's other cells), so one wrong cell does not hide behind another.
- **Compared:** to the cent (R0.01 rounding); text cells as text. `matches` is null when nothing can be compared.
- **Defect codes**, from the formula's shape:
  - for a formula that reads a day sheet (`SUM`, `COUNT`, `SUMIF`, `COUNTIF`): `WRONG_SHEET` (another day's sheet),
    `WRONG_CRITERIA_COLUMN` (criteria range is not the CONSULTANT / budget-list column, or is two columns wide),
    `ROW_OFFSET` (the criteria cell or text is not the row's own label), `WRONG_COLUMN` (adds up another product or
    field), then `TEXT_ID_NOT_COUNTED` (a `COUNT` that shows fewer rows than there are);
  - for a total of other cells: `ROW_OFFSET` (adds another row's cell), `RANGE_INCOMPLETE` (misses a day, or a row
    of the same column just above/below its range), else the most common defect of the inputs that are wrong
    this week, else `BLANK_CONSULTANT_EXCLUDED` (the gap equals the rows without a listed consultant);
  - `THRESHOLD_GREATER_THAN`, `REF_ERROR`, `VALUE_ERROR`; anything else `UNEXPLAINED`.
  - Formula defects are recorded **even when this week's number happens to be right** (`matches = true`), because
    the same formula will be wrong in another week. Only `matches = false` and error cells count as differences.
- `import_jobs.reconciliation_status` = `NOT_CHECKED` (no Report/Breakdown sheet), `MATCHED` or `DIFFERENCES`;
  `reconciliation_differences` = the count. Shown in the import summary; API in `docs/api-imports.md` section 6.
- Differences **never block** an import (D1). Rejected rows and unmapped consultants do.

## 11. Validation codes
`file:not-xlsx` · `file:too-large` · `file:encrypted` · `file:corrupt` · `file:macro-enabled` ·
`sheet:missing-day` (warning) · `sheet:missing-header` · `sheet:unknown-column` (warning) ·
`sheet:unexpected-data` (hidden sheet with data) · `row:invalid-id-number` · `row:id-leading-zero-restored` (warning) ·
`row:missing-name` · `row:invalid-amount` · `row:negative-amount` · `row:formula-error` · `row:date-outside-day` ·
`row:no-consultant` (warning) · `row:unknown-consultant` (blocks confirm until mapped) · `row:consultant-not-active` (warning) ·
`row:quotation-mismatch` / `row:banked-mismatch` (warning, D3) · `client:name-differs` (warning) ·
`client:consultant-differs` (warning) · `lookup:created` (info) · `import:duplicate-file` · `import:week-already-imported`.

## 12. Security and POPIA
- MANAGER/ADMIN only; consultants can never upload.
- `.xlsx` only, 10 MB maximum, POI zip-bomb limits on, no macros, no formula evaluation, no external links.
- The file is **never stored**: only its SHA-256, plus staged rows in `import_rows` until confirm or cancel (or 24 h
  expiry), after which their personal columns are wiped (V3 design).
- Full ID numbers never appear in responses, logs, errors or audit entries. Rows show `*********1234`.
- **Tests use a generated fake workbook** with the exact layout above, fake people and fake checksum-valid IDs.
  **The real workbook is never committed or used in tests.**

## 13. Build plan

| Stage | Contents | Who |
| --- | --- | --- |
| 1 ✅ | Migrations V13–V15 + fake workbook generator (test fixture) | Sifiso |
| 2 ✅ | Parser: sheet detection, header mapping, row rules (sections 2–4) + unit tests | Sifiso |
| 3 ✅ | Preview, consultant mapping, confirm, cancel, summary endpoints + API tests (see `docs/api-imports.md`) | Sifiso |
| 4 ✅ | Report and Breakdown reader + reconciliation (sections 5, 6, 10), V17, endpoints + tests | Sifiso |
| 5 | `/api/reports/weeks/...` endpoints on the views (+ the stored figures for the week's current import) | Daniel |
| 6 | Upload, preview, mapping, summary and report pages | Jack |

## 14. Open questions for the business (defaults in **bold**, used until answered)

| # | Question | Default |
| --- | --- | --- |
| Q1 | "Money generated by a consultant": Banked or Quotation? | **Show both** |
| Q2 | One rule for Quotation and Banked? Thursday/Friday include *Amount to Client* and *3rd Party Settlement*, and Friday's Banked excludes Mafori Card | **Keep each row's value (D3)** and flag days that deviate |
| Q3 | Is the second INSURANCE column (Mon/Tue col Q) a different product? | **Same product, added together** |
| Q4 | Re-importing a week that was already imported | **Blocked; ADMIN "Replace week" with reason, old import kept** |
| Q5 | Target reached at > or ≥ R120,000 (weekly R600,000)? | **≥** |
| Q6 | Breakdown "CARD" sums IVC and "ADVISORY" sums Mafori Voucher: intended? | **Label by the product actually summed**, with the sheet's title shown alongside |
| Q7 | Saturday/Sunday are empty: does the business trade at weekends? | **Import if data exists** |
| Q8 | 119 employer spellings vs the 63 on the Employer Schedule: who maps them? | **Auto-create, admin merges** |
| Q9 | What are the "budget list" structures (Advisory Referr, Cash Advance PA, …) and the loan amounts? | **Stored as given; budget report only** |
