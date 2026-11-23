# Security: two database accounts, and a virus scan of every upload

Two measures from the security list (items 5 and 9). Built by Sifiso on 18 November 2026.

---

## 1. Two database accounts

### What changes
Until now one account (`ca_app`) owned the tables **and** ran the application. Anyone who broke into the running
application had the owner's rights. With those rights they could:
- drop or empty any table;
- switch off the triggers that make the audit log, assignment history and case notes append-only;
- rewrite the migration history.

Now there are two accounts:

| Account | Setting | Used | Rights |
| --- | --- | --- | --- |
| `ca_owner` | `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | by Flyway, at start-up, to migrate | owns every table |
| `ca_app` | `DB_USERNAME` / `DB_PASSWORD` | by the running application | read and write rows; nothing else |

### How `ca_app` gets its rights
`backend/src/main/resources/db/callback/afterMigrate.sql` runs as `ca_owner` after **every** migrate. It runs every
time, not once like a V migration, so tables added later get the same rights automatically. It:
- grants `SELECT, INSERT, UPDATE, DELETE` on all tables, `USAGE, SELECT` on the sequences, and `EXECUTE` on the
  functions;
- takes away `TRUNCATE`, `TRIGGER`, `REFERENCES` and `CREATE` on the schema;
- takes away all rights on `flyway_schema_history`;
- **takes away UPDATE and DELETE** on the append-only tables: `audit_logs`, `client_assignment_history` and
  `case_notes` (both), `credit_cases` (DELETE) and `weekly_report_figures` (UPDATE). Their triggers already refuse
  these. Now the account cannot even try, and it cannot switch the triggers off.

On a laptop and in the tests there is one account, and `spring.flyway.placeholders.approle` is empty, so the
script does nothing. Production sets it to `${DB_USERNAME}`.

### Checks at start-up (production only)
- `SecuritySettingsCheck`, before anything runs, refuses to start if:
  - `DB_MIGRATION_USERNAME` or `DB_MIGRATION_PASSWORD` is missing;
  - the two usernames, or the two passwords, are the same;
  - the placeholder is not `${DB_USERNAME}`.
- `DatabaseAccountVerifier`, after the migrations, asks PostgreSQL what `ca_app` really can do. It refuses to start
  if the account:
  - is a superuser, or may create roles or databases;
  - owns any table;
  - may create tables;
  - may change or delete the audit log;
  - may change the migration history.

### Rule for future migrations
A migration must never contain a dollar sign followed by `{`. Flyway would read it as a placeholder, and start-up
would fail.

---

## 2. Virus scan of every upload

### What changes
Every file uploaded to the application is scanned by **ClamAV** before any code reads it. Today that's the weekly
register and the client list. Anything added later is covered too, because the scan sits in front of every
upload, not in each import.

- **Clean:** the request carries on as before.
- **Virus found:** `422 upload:infected` ("File refused ... (signature)"), nothing is read or stored, and an
  `UPLOAD_REJECTED` audit entry records:
  - the signature;
  - the path;
  - the file's size and extension.

  The file name isn't recorded, because it can contain a name.
- **Could not scan** (ClamAV down, timeout, file too big for it): `503 upload:scan-unavailable`. The file is
  **refused**, never let through unscanned.
- Only signed-in users' uploads reach the scan (signed out: 401 first).

### How
`common/upload/UploadScanFilter` (a servlet filter after Spring Security) sends each uploaded file to `clamd` with
its `INSTREAM` command (`ClamdScanner`). The file is streamed over a local connection and never written to disk.
ClamAV also looks inside the `.xlsx` (it is a zip).

| Setting | Default | |
| --- | --- | --- |
| `app.upload-scan.mode` | `off` (laptop, tests), `clamd` (production) | production refuses to start with `off` |
| `app.upload-scan.clamd-host` / `CLAMD_HOST` | `127.0.0.1` | |
| `app.upload-scan.clamd-port` / `CLAMD_PORT` | `3310` | |
| `app.upload-scan.timeout` | `PT30S` | |

Installing ClamAV, its settings and the EICAR test are in `docs/it-handover.md`, sections 5.2 and 7. To try it on
a laptop:
1. Run `clamd` locally (on Windows, the ClamAV installer).
2. Set `app.upload-scan.mode=clamd` in your `application-local.properties`.
3. Upload the EICAR test file renamed to `.xlsx`.

---

## 3. What was checked (18 November)

Maven is blocked in the development workspace, so it was checked this way:
- **PostgreSQL 16, two real accounts, migrations V1–V30 as `ca_owner`, then the callback: 21 of 21 checks pass.**
  V31 only replaces a view, and the callback grants it again after every migrate.
  - `ca_app` can insert and update clients and consultants, use the code sequence, write and read the audit log,
    and delete import staging rows.
  - `ca_app` is refused:
    - updating, deleting or emptying the audit log;
    - switching off its trigger;
    - creating, changing or dropping a table;
    - reading the migration history;
    - updating case notes or assignment history;
    - deleting credit cases.
  - The callback runs twice without trouble, does nothing with an empty placeholder, and stops with a clear message
    when the named account doesn't exist.
- **The start-up check, against the same database:** `ca_app` passes; `ca_owner` (owns 31 tables, may edit the
  audit log) is refused; a superuser is refused.
- **The virus scan, against a fake clamd that speaks the real protocol:**
  - a 130 KB file goes through in 3 chunks, every byte intact;
  - a virus is refused with 422 and one audit entry, with no file name in it;
  - a scanner error and a scanner that is down are both refused with 503;
  - mode off, and requests that aren't uploads, pass.
- **Production settings:** a correct set passes. Refused: a missing migration account, the same account twice,
  and the scan switched off.
- **Not run here:** a real ClamAV. IT runs the EICAR test before go-live (handover section 7).
