# Consultant onboarding: registration from the register, bulk logins, login emails

Admin only. Built on the weekly register import (`weekly-register-import-design.md`),
`UserService` (logins, password policy, "must change password") and the ID-number encryption key.

## 1. The workflow

| Step | Who / where | What happens |
| --- | --- | --- |
| 1 | Admin, **Import** | Uploads the weekly register. The preview shows which consultants will be registered, matched or are in conflict, and who the new clients go to. |
| 2 | Admin, **Confirm import** | New consultants are registered from their emails, missing emails are filled in, new clients are assigned. All in one transaction. No login is made yet. |
| 3 | Admin, **Consultant logins** → *Generate temporary passwords* | Every active consultant with an email and no login gets a CONSULTANT login with a random temporary password. |
| 4 | Admin, *Send login details* | The emails go into a queue. A background job sends them through Microsoft 365, about 20 a minute. |
| 5 | Admin, *Email delivery report* | Sent / waiting / not sent / failed per consultant. *Retry* resends failed emails with the **same** password. |
| 6 | Consultant | Within 7 days of the email, signs in with the email and temporary password, must choose a new password, then sets up two-step sign-in. |
| 7 | Admin, *Regenerate* | For a login whose emailed password expired unused (or whose email failed): a new temporary password, then *Send login details* again. |

Steps 3 to 5 need the admin's own password, entered in the last 5 minutes (`POST /api/auth/reauthenticate`).

## 2. The CONSULTANT EMAIL column

Optional. Accepted headers: `CONSULTANT EMAIL`, `CONSULTANT E-MAIL`, `CONSULTANT EMAIL ADDRESS`,
`CONSULTANTS EMAIL`, `CONSULTANT'S EMAIL` (V23). A plain `EMAIL` column is **not** used, because it
could be the client's email, and a client must never become a consultant.

Values are trimmed and lower-cased. A value that is not an email address is a warning
(`row:invalid-consultant-email`) and the row falls back to the name. An email on a row without a
consultant name is ignored (`row:email-without-consultant`).

### Matching rules (`ConsultantResolver`, unit-tested in `ConsultantResolverTest`)

The email is the reliable identifier. The name is only trusted when the row has no email.

| Sheet | Database | Result |
| --- | --- | --- |
| email X | a consultant has email X | that consultant; other spellings of the name become aliases |
| email X, name N | nobody has X; N matches one consultant without an email | that consultant; X is added to their record |
| email X, name N | nobody has X; N matches nobody | **registered**: new consultant (generated code, the most-used spelling as the name, others as aliases), ACTIVE |
| email X, name N | X belongs to A but N matches B | **conflict** |
| email X, name N | N matches A, who already has another email | **conflict** (change the sheet, or the consultant's email) |
| name N with two different emails | | **conflict** |
| one email X for names of two different consultants | | **conflict** |
| name only, no email on any of its rows | | unchanged rules: name or alias, else *unmapped* (map it or create it) |

A conflict blocks confirm (`blockers: CONSULTANT_CONFLICTS`, `422 import:consultant-conflicts`), so a
client is never given to the wrong consultant because two people have similar names. A consultant
to be registered whose name looks like an existing consultant is listed with `similarConsultants`,
so the admin can check it is not the same person under a second email.

### Client assignment (unchanged rules, new report)

- A new client is assigned to the row's consultant if that consultant is ACTIVE (including one
  registered by this import).
- An existing client keeps their consultant. A client a manager unassigned stays unassigned.
- The preview's `assignment` shows `newClientRows`, `willBeAssigned`, `noConsultant`,
  `consultantNotActive`, `waitingForConsultant` (unmapped or conflict) and
  `existingClientsWithOtherConsultant`. Repeat rows and rejected rows are in `repeatRows` and
  `rejectedRows` as before.
- The confirm result adds `consultantsRegistered`, `consultantEmailsAdded`,
  `consultantAliasesAdded` and `newClientsUnassigned`.

## 3. Temporary passwords

- 16 random characters (`TemporaryPasswordGenerator`, about 98 bits), always upper, lower, digit
  and symbol, no look-alike characters. Never repeated within a batch.
- The login is made with `UserService.create`, so the password policy, unique email, one login per
  consultant and *must change password* all apply. `users` only ever holds the BCrypt hash.
- A consultant who already has a login is never touched, so re-running never overwrites a password.
- Eligible = consultant ACTIVE, has an email, no login of their own, and no other login uses that
  email. `GET /api/onboarding/blocked` lists the active consultants who cannot get one yet.
- Until its email is sent, the password is kept **only** sealed with AES-256-GCM
  (`IdNumberProtector.sealText`, same key as ID numbers, bound to the purpose
  `consultant-temporary-password:v1`) in `consultant_login_invites.password_sealed`. It is deleted in
  the same statement that marks the email SENT (a CHECK constraint enforces it). Because it shares
  `ID_NUMBER_ENCRYPTION_KEY`, rotating that key affects queued emails (PENDING, QUEUED, FAILED): send them
  first or keep the old key for decryption, otherwise regenerate them (`it-handover.md`, section 4).
- 25 logins per call (one BCrypt hash each). The admin page calls again until none remain.

### Time limit (V24)

The first sign-in also sets up two-step sign-in, so whoever reads a login email first owns the account. An
**emailed** temporary password therefore works for `app.onboarding.temporary-password-days` (default **7**)
after Microsoft 365 accepted the email.

- `users.temporary_password_expires_at` is set when the email is sent (`LoginDetailsSender`) and cleared by
  every password change (`UserAccount.changePasswordHash`): the consultant's own new password, or an admin
  reset. **Passwords an admin resets and hands over in person never expire.**
- After the limit, sign-in with the right temporary password answers `401 auth:temporary-password-expired`
  ("Ask your administrator to send you new login details"). A wrong password still gets the generic answer,
  so the message tells a guesser nothing. Audited as `TEMPORARY_PASSWORD_EXPIRED`.
- The report marks such logins **Expired** (filter `status=EXPIRED`). *Regenerate*
  (`POST /api/onboarding/invites/regenerate`) gives each expired or failed login a new temporary password
  through `UserService.resetPassword`, seals it into the same invite and sets it back to PENDING; *Send login
  details* then emails it and starts a new time limit. The old password stops working at once.
- The email itself says how long the password works.

## 4. Login emails

- One email per consultant with only their own details: sign-in address (`app.onboarding.login-url`),
  their email, their temporary password, first sign-in steps (`LoginDetailsEmail`).
- `LoginDetailsSender` runs every 30 s and sends up to 10 (Microsoft 365 allows about 30 a minute
  per mailbox). `FOR UPDATE SKIP LOCKED`: two servers never send the same email.
- Before sending it checks that the login is still enabled and still has the generated password.
  If an admin reset it meanwhile, the old password is **not** sent (FAILED, with the reason).
- Temporary failure: tried again after 2, 4 and 8 minutes, FAILED after 4 attempts. Permanent
  failure (bad address, HTTP 400): FAILED at once. Throttling (429/503): the round stops.
- *Retry* puts FAILED emails back in the queue with the same sealed password.
- The audit log records `LOGIN_INVITE_CREATED`, `LOGIN_DETAILS_QUEUED`, `LOGIN_DETAILS_SENT`,
  `LOGIN_DETAILS_FAILED`, `LOGIN_DETAILS_RETRIED`, `CONSULTANT_CREATED` (source
  `WEEKLY_REGISTER_EMAIL`), `CONSULTANT_EMAIL_SET`, `CONSULTANT_ALIAS_CREATED`. Never a password.

## 5. Mail set-up (IT)

The app sends with **Microsoft Graph**, not SMTP with a password: Microsoft turns off basic SMTP
authentication for existing Microsoft 365 tenants by default at the end of December 2026 and will
remove it later.

1. Create a shared mailbox to send from, e.g. `no-reply@mafori.co.za`.
2. In Entra ID, register an app (e.g. *Credit Advisory mail*). Add the **application** permission
   Microsoft Graph → `Mail.Send` and grant admin consent. Create a client secret and note when it
   expires.
3. Limit the app to that one mailbox (otherwise `Mail.Send` can send as anyone): Exchange
   *RBAC for Applications*, or an *application access policy*
   (`New-ApplicationAccessPolicy -AccessRight RestrictAccess -PolicyScopeGroupId <group with the mailbox>`).
4. Set the environment variables on the server:

| Variable | Value |
| --- | --- |
| `MAIL_GRAPH_TENANT_ID` | Directory (tenant) ID |
| `MAIL_GRAPH_CLIENT_ID` | Application (client) ID |
| `MAIL_GRAPH_CLIENT_SECRET` | the client secret (a secret: keep it out of git) |
| `MAIL_SENDER` | the mailbox, e.g. `no-reply@mafori.co.za` |
| `APP_LOGIN_URL` | the address consultants sign in at, e.g. `https://credit-advisory.mafori.co.za` |
| `ONBOARDING_SUPPORT_CONTACT` | who to contact, shown in the email (default "your manager") |

The server must reach `login.microsoftonline.com` and `graph.microsoft.com` over HTTPS. Until the four
`MAIL_*` values are set, the page shows "Email is not set up" and *Send login details* answers
`503 mail:not-configured`. Renew the client secret before it expires, or sending stops (the report
shows the sign-in failure).

## 6. API (all ADMIN)

| Method and path | Body | Answer |
| --- | --- | --- |
| `GET /api/onboarding/summary` | | counts of consultants, eligible, need attention, invites by status, `mailConfigured`, `mailSender`, `loginUrl` |
| `GET /api/onboarding/blocked?page&size` | | active consultants without a login who cannot get one: `NO_EMAIL` or `EMAIL_USED_BY_ANOTHER_LOGIN` |
| `POST /api/onboarding/logins` | `{}` or `{"consultantIds":[..]}` | `{created, remaining, skipped[]}`; 403 `auth:reauthentication-required` |
| `GET /api/onboarding/invites?status&page&size` | | the delivery report (never a password) |
| `POST /api/onboarding/invites/send` | `{}` or `{"inviteIds":[..]}` | `{queued}`: PENDING → QUEUED; 503 when mail is not set up |
| `POST /api/onboarding/invites/retry` | `{}` or `{"inviteIds":[..]}` | `{queued}`: FAILED → QUEUED, same password |
| `POST /api/onboarding/invites/regenerate` | `{}` or `{"inviteIds":[..]}` | `{created, remaining, skipped[]}`: new passwords for expired (sent, unused, past the limit) or failed logins, back to PENDING; needs the admin's password again |
| `PUT /api/onboarding/consultants/{id}/email` | `{"email":".."}` | sets a missing or wrong email; 409 if another consultant has it or the consultant already has a login |

## 7. Known limits

- **Very large workbooks.** The reader loads the whole workbook into memory (POI user model) and
  uploads are capped at 10 MB (`app.imports.max-file-bytes`, `spring.servlet.multipart.*`). A
  65,000-row first load may need a bigger cap and JVM heap, or splitting into several files.
  Confirm and preview still write staged rows one by one. Both are worth moving to streaming and
  batched inserts before a load that size.
- Microsoft 365 limits one mailbox to about 10,000 recipients a day; 300+ consultants is far below.
- A consultant's email is not changed when their login's email changes (and vice versa). Change both.
