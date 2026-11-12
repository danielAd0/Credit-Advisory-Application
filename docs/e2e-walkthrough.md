# End-to-end walkthrough - the whole system, by hand

One run through the real backend and the real frontend together, with fake data only. The automated tests check
each endpoint on its own; this checks that the screens and the API agree, and that the numbers line up from
capture to import to reports.

Takes about an hour. Write every problem in the table at the end, with whose area it is, and fix only your own.

**Fake data only.** Never use the real workbook or a real client. The ID numbers below are made up (valid format,
nobody's number). The fake workbook stays **outside** the repository and is never committed.

---

## 0. Set up

1. Pull, then start the backend (it runs on port 8080):

```powershell
cd C:\dev\Credit-Advisory-Application
git pull
cd backend
.\mvnw.cmd spring-boot:run
```

2. In a second PowerShell window, start the frontend (port 5173). No `VITE_USE_MOCK`: this must talk to the real
   backend.

```powershell
cd C:\dev\Credit-Advisory-Application\frontend
npm install
npm run dev
```

3. In a third window, make the fake weekly workbook for the week of **2 November 2026**. It is written to
   `C:\dev\fake-data`, outside the repository, then given the name the import reads the week from:

```powershell
New-Item -ItemType Directory -Force C:\dev\fake-data
cd C:\dev\Credit-Advisory-Application\backend
.\mvnw.cmd -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook" "-Dexec.classpathScope=test" "-Dexec.args=C:\dev\fake-data\fake.xlsx"
Rename-Item C:\dev\fake-data\fake.xlsx "Credit Advisory Online Dashboard (2 - 8 Nov 2026).xlsx"
```

4. Use **three separate browser windows** so the three people stay signed in at once: a normal window, a private
   (InPrivate / Incognito) window, and a second browser. Always use `http://localhost:5173`, not `127.0.0.1`.

Two-step sign-in is off locally (`app.mfa.required=false`), so nobody is asked for a code. Section 9 turns it on.

---

## 1. Accounts (ADMIN, window 1)

| # | Do | Expect |
|---|---|---|
| 1.1 | Sign in with the admin from your `application-local.properties` | Overview page; the menu has Administration (Managers, Audit log) |
| 1.2 | Managers: add `manager@local.test` with a temporary password | It appears in the list |
| 1.3 | Team: add consultant **TEST CONSULTANT ONE** with a login `consultant1@local.test` | It appears with a consultant code; status ACTIVE |
| 1.4 | Team: add **TEST CONSULTANT TWO** with a login `consultant2@local.test` | Same |
| 1.5 | Window 2: sign in as `consultant1@local.test` | Sent straight to "choose your own password"; nothing else opens until it is done |
| 1.6 | Window 3: sign in as `manager@local.test`, choose a password | Menu has Team, Targets, Import, Reassign clients; **no** Consultant actions, **no** Managers or Audit log |

## 2. A client and a typed-in sale (CONSULTANT ONE, window 2)

| # | Do | Expect |
|---|---|---|
| 2.1 | Clients: add **Test Walkthrough**, ID number `9001019001089`, any employer and branch | Saved; the list shows the ID masked (last 4 digits only) |
| 2.2 | Add a second client with ID `9001019001089` again | Refused: duplicate ID number |
| 2.3 | Consultant actions: capture a sale for Test Walkthrough dated **3 Nov 2026**: Combo 1500, Credit repair 300.50 | Saved once, even if you double-click Save. Quotation total 1800.50, banked 1500.00 (Credit repair does not count toward banked) |
| 2.4 | Try a sale dated tomorrow | Refused: date in the future |
| 2.5 | Sales history, 2 Nov - 8 Nov 2026 | The 3 Nov sale, with the client name and your name; totals 1800.50 / 1500.00 |
| 2.6 | Open the client | The sale is on the client page |
| 2.7 | If Jack's Edit / Delete buttons are in: change Credit repair to 500, save; then capture a second small sale and delete it | Totals follow (quotation 2000.00); the deleted one disappears everywhere. If the buttons are not in yet, skip and note it |

## 3. The manager's view (MANAGER, window 3)

| # | Do | Expect |
|---|---|---|
| 3.1 | Reports, week of 2 Nov 2026 | The week shows, with the same quotation and banked totals as Sales history in 2.5 / 2.7 |
| 3.2 | Sales history, same range, filter by TEST CONSULTANT ONE then TWO | ONE shows the sale; TWO shows nothing |
| 3.3 | Window 2 (consultant): Sales history | Only their own sales; no consultant filter, or one that only offers themselves |
| 3.4 | Targets: set a target starting today | Saved; a start date in the past is refused |

## 4. Import the week (MANAGER, window 3)

| # | Do | Expect |
|---|---|---|
| 4.1 | Import: upload the fake workbook from `C:\dev\fake-data` | Week read from the name: 2 Nov 2026. The preview says **1 sale was typed into the app** for this week and confirming replaces it |
| 4.2 | Look at the issues | Warnings and errors listed per row. One consultant, **UNMAPPED PERSON**, is not known |
| 4.3 | Try to confirm before dealing with UNMAPPED PERSON | Refused until it is mapped or added |
| 4.4 | Map UNMAPPED PERSON to TEST CONSULTANT TWO; add any other unknown consultants it offers | Confirm is allowed |
| 4.5 | Confirm | Import COMMITTED; clients and sales created |
| 4.6 | Issues: is there a way to download them as CSV? | Known gap (FR-12, Jack): the endpoint exists, the button may not. Note it |

## 5. After the import: do the numbers agree?

| # | Do | Expect |
|---|---|---|
| 5.1 | Sales history, 2 - 8 Nov 2026 (manager) | The typed-in sale from 2.3 is **gone** (the spreadsheet replaced it); the imported sales are listed instead. The API marks each row `typedIn` true or false: if the page does not show the difference, note it (Jack) |
| 5.2 | Reports, week of 2 Nov 2026 | Quotation and banked totals are **the same** as the Sales history totals in 5.1. Write both numbers down |
| 5.3 | Test Walkthrough's client page | The typed-in sale still shows. The API sends it with `replaced: true`: if the page shows it like any other sale, note it (Jack) |
| 5.4 | Consultant actions (window 2): capture another sale dated 4 Nov 2026 | Refused: that week's spreadsheet is already imported |
| 5.5 | If the Edit button is in: try to edit the replaced sale | Not offered, or refused with "fix it in the spreadsheet" |

## 6. Reassigning (MANAGER, window 3)

| # | Do | Expect |
|---|---|---|
| 6.1 | Reassign clients: move Test Walkthrough from ONE to TWO, with a reason | Done; assignment history shows who, when and the reason |
| 6.2 | Window 2 (ONE): Clients, and refresh the client page | Test Walkthrough is no longer listed; opening it says not found |
| 6.3 | Try to reassign to a consultant set to SUSPENDED (Team: set TWO to SUSPENDED first, then back) | Refused |

## 7. Shutting a consultant out (ADMIN or MANAGER)

| # | Do | Expect |
|---|---|---|
| 7.1 | Team: set TEST CONSULTANT ONE to SUSPENDED | Saved |
| 7.2 | Window 2 (ONE): click anything | Signed out at once; signing in again is refused |
| 7.3 | Set ONE back to ACTIVE; sign in again | Works |

## 8. The audit trail (ADMIN, window 1)

| # | Do | Expect |
|---|---|---|
| 8.1 | Open Test Walkthrough, press Show ID number | Asks for the password first, then shows the full number |
| 8.2 | Audit log | Entries for: the accounts, the client, SALE_CAPTURED (and SALE_EDITED / SALE_DELETED if 2.7 ran), the import, the reassignment, the suspension, the ID number shown, the target |
| 8.3 | Search the audit log entries' details for `9001019001089` | **Never** there: no full ID number in any entry |
| 8.4 | Browser developer tools (F12) > Console, on a few pages | No red errors. Network tab: no full ID number in any URL |

## 9. Optional: with two-step sign-in on

Only if there is time. In `application-local.properties` set `app.mfa.required=true` and add your own
`app.mfa.encryption-key` (the README says how, under "Keys"; never one of the ID number keys). Restart the backend.

| # | Do | Expect |
|---|---|---|
| 9.1 | Sign in as consultant ONE | Asked to set up an authenticator: QR code, then 10 recovery codes shown once |
| 9.2 | Sign out and in again | Asked for the 6-digit code |
| 9.3 | Sign in with a recovery code instead | Works once; the same code again is refused |
| 9.4 | Move to a new phone (if Jack's screen is in): password, new QR code, code from the new phone | 10 new recovery codes; other windows of ONE are signed out |

Set `app.mfa.required=false` again afterwards if you want to.

---

## Findings

| # | Step | What happened | What should happen | Whose area |
|---|---|---|---|---|
| 1 | Team > Manage | There is no way to remove a consultant who left; only a status drop-down | A clear "Remove consultant (left the company)" button that sets them to INACTIVE with a reason (never a delete: their sales and audit history point at them). If they still have clients, it opens Reassign clients with that consultant as "from" and all their clients ticked; with 0 clients, back to the Team list. Backend needs nothing new | Dan / Jack (Team page), Jack (Reassign page reading `?from=`) |
| 2 | Managers > Manage > Access | "Also an administrator" tick box drawn as a wide empty bar | A small tick box on the label's line | Fixed in `c3b46e6` (addclient.css; also fixes the Products form tick boxes) |
| 3 | | | | |
| 4 | | | | |
| 5 | | | | |

### Waiting on other parts (known before the run)

Steps that cannot pass until these exist. Note them in the table only if they behave differently from this list.

| Feature | Backend | Frontend |
|---|---|---|
| Edit / Delete a typed-in sale (2.7, 5.5) | done: `GET`/`PATCH`/`DELETE /api/transactions/{id}` | Jack |
| Show a replaced or imported sale as such (5.1, 5.3) | done: `typedIn`, `replaced` on every sale | Jack |
| Download an import's issues as CSV (4.6, FR-12) | done | Jack |
| Move two-step sign-in to a new phone (9.4) | done: `POST /api/auth/mfa/change`, `/change/confirm` | Jack |
| First import of the full client list (FR-07 to FR-11) | Sifiso | Sifiso |

When it is done: delete `C:\dev\fake-data`, or keep it outside the repository. Nothing from this run is committed
except this file's findings, if the team wants them kept.
