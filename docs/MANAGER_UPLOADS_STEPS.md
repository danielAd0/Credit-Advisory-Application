# Managers can see when the admin uploads a file

What you asked for (9 November): "Allow the managers to see when a new file has been uploaded by the admin."

Built on the latest code (18 November). Running imports stays **admin only**. A manager can now **look**, and
nothing else:

| | Admin | Manager | Consultant |
| --- | --- | --- | --- |
| Import history (`GET /api/imports`) | yes | **yes (new)** | no |
| One import's summary (`GET /api/imports/{id}`) | yes | **yes (new)** | no |
| Upload, week guess, rows, issues, CSV download, workbook check, consultant check, confirm, cancel, name spellings | yes | no | no |

Neither the list nor the summary holds client data: no client names and no ID numbers, not even the last four
digits. The summary's consultant list holds staff names and emails, which managers already see on the Team page.
The rows and the issues download hold client data, so they stay admin-only.

This replaces the package from 9 November, which was never merged and no longer fits the code. That package's
test used the old name-mapping step, which Ntiyiso has replaced with the consultant check.

## What's in it

| File | New / changed | What |
| --- | --- | --- |
| `backend/.../imports/weekly/WeeklyImportController.java` | Changed | Two annotations: `GET /api/imports` and `GET /api/imports/{id}` also for MANAGER |
| `backend/src/test/java/.../imports/WeeklyImportApiTest.java` | Changed | One line removed: it checked that a manager gets 403 on the list |
| `backend/src/test/java/.../imports/ImportManagerViewApiTest.java` | New | 4 tests (below) |
| `docs/api-imports.md` | Changed | Section 1 "Who can use it" table, and "Who" on the list and the summary |

The new tests:
1. After the admin imports the fake week, a manager sees it in the list (COMMITTED, the week, when) and opens its
   summary. The summary has no ID number and no client surname.
2. A manager also sees a preview the admin hasn't confirmed yet, and looking changes nothing.
3. A manager gets **403** on everything else. Each refused request has a valid body, so every 403 is the role
   check and not a validation error.
4. A consultant gets 403, and a signed-out request gets 401.

## What was checked here

- The new test compiles against the current test helpers and the fake workbook.
- The controller change is two `@PreAuthorize` lines. In Spring Security a rule on a method replaces the rule on its
  class, so these two endpoints become MANAGER + ADMIN while every other endpoint keeps the class's ADMIN rule.
  The new tests prove it when you run `mvnw test`. Maven is still blocked in my workspace.

## Steps (PowerShell, from the repository root)

1. **Get the latest code**
   ```powershell
   git pull
   ```

2. **Copy the files in, `backend` AND `docs`** (unzip outside the repo first, e.g. `C:\dev\manager-uploads`)
   ```powershell
   $pkg = "C:\dev\manager-uploads"
   Copy-Item "$pkg\backend\*" ".\backend\" -Recurse -Force
   Copy-Item "$pkg\docs\*"    ".\docs\"    -Recurse -Force
   git status
   ```
   **Modified:** `WeeklyImportController.java`, `WeeklyImportApiTest.java`, `docs/api-imports.md`.
   **New:** `ImportManagerViewApiTest.java`.
   If Ntiyiso changed `WeeklyImportController.java` after 18 November, don't copy it over his. Add the two
   `@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")` lines above `list(` and `summary(` by hand instead.

3. **Run the tests**
   ```powershell
   cd backend
   .\mvnw.cmd clean test
   cd ..
   ```
   Everything must pass, including the 4 new tests. If one fails, send me its name and message.

4. **Commit**
   ```powershell
   git add backend docs
   git status
   git commit -m "Managers can see the import list and summaries (read-only); running imports stays admin-only"
   git pull
   git push
   ```

## For Jack

- An **Uploads** page for MANAGER. The Import menu itself stays ADMIN only.
- It's a read-only list from `GET /api/imports`: week, state, uploaded by, uploaded at, committed at.
- Clicking a row opens the summary (`GET /api/imports/{id}`) with no buttons. Don't link to rows, issues or the
  workbook check: a manager gets 403 there.
- Optional: a **New** badge when the newest `COMMITTED` import is newer than the one the manager last opened. Keep
  that in the browser; it's only a convenience.
