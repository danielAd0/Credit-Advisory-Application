# Security: two database accounts and a virus scan of uploads (plus the backslash dates)

What's in this package, built on the latest code (18 November):

1. **Two database accounts.** The running application can no longer drop tables, switch off triggers or edit
   the audit log. Only the migration account can change the database's structure.
2. **Virus scan of every upload** (ClamAV). An infected file is refused, and so is a file that can't be
   scanned.
3. **The weekly register accepts dates typed as `09\11\2026`.** That way of typing a date made the 9–13 November
   register lose 493 of its 509 rows.

Details for Ntiyiso and IT are in `docs/security-accounts-and-uploads.md`.

## What it means in practice

| | Laptop and tests | Production server |
| --- | --- | --- |
| Database accounts | One, as now. Nothing changes | Two: `ca_owner` (migrations) and `ca_app` (the app). The app refuses to start if they are the same, or if `ca_app` can do more than read and write rows |
| Virus scan | Off, unless you switch it on | On. The app refuses to start without it. ClamAV runs on the server |

**Nothing changes for you, Jack or Ntiyiso on your laptops**, and none of the existing tests change.

## What's in it

### New files
| File | What it does |
| --- | --- |
| `backend/src/main/resources/db/callback/afterMigrate.sql` | After every migrate, gives the app's account its rights and takes away the dangerous ones |
| `backend/.../common/config/SecuritySettingsCheck.java` | Production: refuses to start without two different accounts and the virus scan |
| `backend/.../common/config/DatabaseAccountVerifier.java` | Production: before the web server starts, asks the database what the app's account can really do |
| `backend/.../common/upload/UploadScanner.java`, `ClamdScanner.java`, `UploadScanFilter.java` | The scan of every upload, through ClamAV |
| `backend/src/test/java/.../common/config/SecuritySettingsCheckTest.java` | 3 tests on the settings |
| `backend/src/test/java/.../common/config/DatabaseAccountVerifierTest.java` | 2 tests: the one-account test database is refused, and the grants step does nothing there |
| `backend/src/test/java/.../common/upload/ClamdScannerTest.java` | 5 tests against a fake ClamAV |
| `backend/src/test/java/.../common/upload/UploadScanFilterTest.java` | 6 tests: clean, infected (422 and audit), can't scan (503), off, several files |
| `backend/src/test/java/.../imports/register/WeeklyRegisterParserBackslashDateTest.java` | 3 tests on the backslash dates |
| `docs/security-accounts-and-uploads.md` | How both measures work, and what was checked |

### Changed files
| File | Change |
| --- | --- |
| `backend/src/main/resources/application.properties` | Flyway also reads `db/callback`. Empty `approle` placeholder. Upload scan settings, `off` |
| `backend/src/main/resources/application-prod.properties` | Flyway's own account (`DB_MIGRATION_USERNAME`/`PASSWORD`). `approle` = `DB_USERNAME`. Scan `clamd` |
| `backend/.../imports/register/WeeklyRegisterParser.java` | One line: the date format `d\M\uuuu` |
| `docs/it-handover.md` | Section 3: create two accounts, plus the steps for a database that already exists. Section 5.2: the new variables and installing ClamAV. Section 7: the EICAR test before go-live |
| `README.md` | The variables table, and network checklist item 6: the outgoing connections that are allowed (Microsoft 365, which was missing, and the ClamAV updates) |

**Ntiyiso's files:** `WeeklyRegisterParser.java`, both properties files, `it-handover.md` and `README.md` are his. The
changes in them are small and listed above. If he has changed any of them after 18 November, make the same change by
hand instead of copying over his version.

## What was checked here

Maven is still blocked in my workspace, so I checked it this way:
- **PostgreSQL 16 with two real accounts:** migrations V1–V28 ran as `ca_owner`, then the grants step. **21 of 21
  checks pass.**
  - The app's account does all its normal work.
  - It is refused:
    - changing, deleting or emptying the audit log;
    - switching off its trigger;
    - creating, changing or dropping a table;
    - reading the migration history;
    - editing case notes or assignment history;
    - deleting a credit case.
  - The grants step can run twice. It does nothing with one account, and stops clearly if the account doesn't
    exist.
- **The start-up checks: 20 of 20 pass.**
  - Against that database, `ca_app` passes, while `ca_owner` and a superuser are refused.
  - The virus scan, against a fake ClamAV that speaks the real protocol: a clean file arrives byte for byte, a
    virus gets 422 plus an audit entry without the file name, and a scanner that errors or is down gets 503.
  - The production settings checks pass for a correct set and refuse the bad ones.
- **The backslash dates:** the 3 new tests and the existing parser tests pass.
- **Not run here:** Spring itself and a real ClamAV. `mvnw test` covers Spring. IT checks ClamAV with the EICAR
  file (handover section 7).

## Steps (PowerShell, from the repository root)

1. **Get the latest code**
   ```powershell
   git pull
   ```

2. **Copy the files in** (unzip outside the repo first, e.g. `C:\dev\security`)
   ```powershell
   $pkg = "C:\dev\security"
   Copy-Item "$pkg\backend\*" ".\backend\" -Recurse -Force
   Copy-Item "$pkg\docs\*"    ".\docs\"    -Recurse -Force
   Copy-Item "$pkg\README.md" ".\README.md" -Force
   git status
   ```
   **Modified:** the two properties files, `WeeklyRegisterParser.java`, `it-handover.md`, `README.md`.
   **New:** `db\callback\afterMigrate.sql`, the `common\upload` folder, the two config classes, the 5 test files, and
   `docs\security-accounts-and-uploads.md`.

3. **Run the tests**
   ```powershell
   cd backend
   .\mvnw.cmd clean test
   ```
   Everything must pass, including the 19 new tests. If one fails, send me its name and message.

4. **Start the app on your laptop once** (`.\mvnw.cmd spring-boot:run`). It must start as before: one account, scan
   off. The log shows Flyway running the `afterMigrate` callback.

5. **Commit**
   ```powershell
   cd ..
   git add backend docs README.md
   git status
   git commit -m "Security: separate migration and application database accounts, ClamAV scan of uploads; accept dd\mm\yyyy dates"
   git pull
   git push
   ```

## For IT (before go-live), all in `docs/it-handover.md`

- Create `ca_owner` and `ca_app` (section 3), and set `DB_MIGRATION_USERNAME` and `DB_MIGRATION_PASSWORD`.
- Install ClamAV with `clamd` on `127.0.0.1:3310` and `freshclam` (section 5.2). Allow `database.clamav.net:443`.
- Run the EICAR test, and check that `ca_app` can't change the audit log (section 7).

## Your security list after this

| # | Item | Status |
| --- | --- | --- |
| 5 | Separate database accounts, an audit log nobody can edit | Done (this package) |
| 6 | Keys outside the code, key rotation | Done (Ntiyiso, `keys` package) |
| 9 | Safer uploads: virus scan, zip-bomb limit | Done (scan: this package; limits: already in the reader) |

Still open: the end-to-end test with the real workbook on the secured server.
