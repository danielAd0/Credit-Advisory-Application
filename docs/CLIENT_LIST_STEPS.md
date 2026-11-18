# First load of the full client list

What you asked for: an import for the **launch client list**, the full list of clients the business already has,
loaded once before the first weekly register import. It uses Ntiyiso's layout (V20): name, surname, ID number,
consultant, email, cell, work and home phone, C_BRANCHNAME, employer (or DEBIT ORDER), File number and File ID.

Built on the latest code (18 November, migrations up to V28). **Everything is new files**: nothing of Ntiyiso's
or of the weekly register import changed. This replaces the package from 16 November, whose migration was V27;
Ntiyiso has since used V27 and V28, so it is now **V29**.

## How it works

1. The admin uploads the list (`POST /api/imports/client-list/preview`). Nothing changes yet. The preview shows:
   - how many clients are new, how many are already stored, how many rows have problems;
   - which column header became which field;
   - the consultants it found: existing, to be registered, or in conflict;
   - the employers and product quotations it will add.
2. The admin confirms (`POST /api/imports/client-list/{id}/confirm`). In one transaction it:
   - creates the clients with all their details;
   - assigns each client to their consultant if that consultant is ACTIVE;
   - registers new consultants;
   - adds new employers (marked "needs review") and new product quotations.
3. The staged names, ID numbers and phone numbers are deleted when the import ends: confirmed, cancelled, or after
   24 hours.

**Safe by design:**
- A client already stored under the **same** name only gets the fields they don't have yet. Nothing is
  overwritten, and an assigned client keeps their consultant.
- Under **another** name, the row is not imported, and a person checks that client.
- Loading the same list again creates nothing twice.

**Admin only**, like every import. The full API for Jack is in `docs/api-client-list-import.md`.

## Two assumptions (tell me if either is wrong)

1. **"Email" is the client's email.** The test sheet had Email next to Consultant, and the weekly register's email
   is the consultant's. If the launch list's Email is the consultant's, it's one line in a new migration (doc,
   section 2).
2. **The Date column is not imported.** It was empty in all three rows of the test sheet, and no column in the
   database fits it. It shows as "not imported" in the preview. If it means something (the date the client
   joined), I'll add a field for it.

The real header names of the launch list may differ slightly from what I guessed (for example "CELL NO" or "Tel
(W)"). The preview shows any unknown header as "not imported". Each new spelling is one row in
`client_list_header_aliases`. Upload the real list once on the **secured server**, look at the `columns` section of
the preview, and **Cancel**. That tells us whether a spelling is missing, without importing anything.

## What's in it

### New files
| File | What it does |
| --- | --- |
| `backend/src/main/resources/db/migration/V29__client_list_import.sql` | The header spellings (74), the extra staged columns, the column mapping on the job |
| `backend/.../imports/clientlist/ClientListField.java` | The fields the list can have |
| `backend/.../imports/clientlist/ClientListParser.java` | Reads the sheet: finds the header row, cleans and checks each value. No database |
| `backend/.../imports/clientlist/ClientListStore.java` | All SQL of this import |
| `backend/.../imports/clientlist/ClientListImportService.java` | Preview, summary, confirm, rows, issues, CSV, cancel, expiry |
| `backend/.../imports/clientlist/ClientListImportController.java` | `/api/imports/client-list/...` (ADMIN) |
| `backend/.../imports/clientlist/ClientListViews.java` | Request and response shapes |
| `backend/.../imports/clientlist/ClientListCleanupJob.java` | Expires old previews every hour, like the weekly one |
| `backend/src/test/java/.../imports/clientlist/ClientListParserTest.java` | 7 tests on the reading rules |
| `backend/src/test/java/.../imports/clientlist/ClientListImportApiTest.java` | 6 API tests: preview, confirm and what is stored, the consultant check, issues and CSV, cancel, roles |
| `backend/src/test/java/.../imports/clientlist/FakeClientListWorkbook.java` | A fake list (made-up people) for the tests |
| `docs/api-client-list-import.md` | The API, the rules and the screen for Jack |

## What was checked here

Maven is still blocked from my workspace, so I checked it this way, on the 18 November code:

- **The parser tests: 7 of 7 pass.** They cover the layout, a header row under a title, required fields, values
  left out with a warning, file-level errors, rows with no client, and messages that never contain a name or an ID
  number.
- **A full run against PostgreSQL 16 (V1–V29), through the real service and SQL: 68 of 68 checks pass.** That
  includes Ntiyiso's V27, so the run starts, like a new install, with no employers or products. It covers:
  - the preview counts, the column mapping, the consultants, and the new employers and product quotations;
  - no ID number in any message, and nothing written to the clients before confirm;
  - confirm refused for the wrong file, the wrong consultant list, or a consultant who changed after the preview;
  - after confirm: every field stored, the phones as +27, the ID decrypts, the assignments and their history, a
    new consultant registered, DEBIT ORDER, an inactive consultant (the client is left unassigned), filled-in
    fields, a name conflict left alone, a manager's unassign respected, the audit entries, and the staged personal
    data deleted;
  - the same list again: nothing new. Cancel, expiry, blocked files, and an ambiguous consultant name.
- **Everything compiles:** the new package together with the whole import code, and the Spring tests.
- Your test file: I only read its headers and counts, then deleted my copy.

**Not run here:** Spring itself (security, JSON, transactions). `ClientListImportApiTest` covers those in
`mvnw test`.

## Steps (PowerShell, from the repository root)

1. **Get the latest code and check the migration number**
   ```powershell
   git pull
   dir backend\src\main\resources\db\migration
   ```
   The last should be **V28** (Ntiyiso's sale correction flags). If someone has added a V29, rename ours to the next
   free number.

2. **Copy the files in, `backend` AND `docs`** (unzip outside the repo first, e.g. `C:\dev\client-list`)
   ```powershell
   $pkg = "C:\dev\client-list"
   Copy-Item "$pkg\backend\*" ".\backend\" -Recurse -Force
   Copy-Item "$pkg\docs\*"    ".\docs\"    -Recurse -Force
   git status
   ```
   Nothing should be **modified**. **New:** V29, the `imports\clientlist` folder (main and test), and
   `docs\api-client-list-import.md`. If you still have the 16 November package unzipped, don't copy from it: it
   has the old V27 name.

3. **Run the tests**
   ```powershell
   cd backend
   .\mvnw.cmd clean test
   ```
   Everything must pass, including the 13 new tests. If one fails, send me its name and message.

4. **Try it** (optional, fake data only). Start the app and sign in as the admin:
   - `POST /api/imports/client-list/preview` with a small made-up list (or `FakeClientListWorkbook`).
   - Check the summary, then `POST /api/imports/client-list/{id}/confirm` with the `fileDigest` and
     `consultants.fingerprint` from the summary.
   - The clients show on the Clients page with their phones, branch, File number and consultant.

5. **Commit**
   ```powershell
   cd ..
   git add backend docs
   git status
   git commit -m "Client list import: first load of the full client list (V29), ADMIN only"
   git pull
   git push
   ```

## For Jack

An **Admin > Import client list** page, separate from the weekly import (doc, section 8):
- upload, then the summary: counts, columns, consultants, new employers and branches, and the issues with a CSV
  download;
- **Confirm** sends `fileDigest` and `consultants.fingerprint`. On `409 import:consultants-changed`, reload and ask
  again;
- **Cancel**.

## Before go-live

The order on go-live day:
1. Admin accounts.
2. The client list (this import).
3. The first weekly register.

The client list must go first. Then the weekly register finds every client by ID number instead of creating them
with only a name.
