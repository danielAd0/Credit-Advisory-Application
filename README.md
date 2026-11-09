# Credit Advisory Application

Web application replacing the spreadsheet-based credit advisory process:
client and consultant management, Excel import, client handover and credit case tracking.

## Team

| Member  | Area                                              |
| ------- | ------------------------------------------------- |
| Ntiyiso | Backend: identity, clients, consultants           |
| Sifiso  | Backend: Excel import, migrations, data integrity |
| Jack    | Frontend and dashboards                           |

## Stack

- **Backend:** Java 21, Spring Boot 4.1, Maven (wrapper included - no Maven install needed)
- **Database:** PostgreSQL 16, schema managed by Flyway migrations
- **Frontend:** React, TypeScript 6.0, Vite, ESLint, Prettier

## Requirements

Install these before anything else:

- JDK 21 - check with `java -version`
- PostgreSQL 16 with pgAdmin 4 and Command Line Tools (untick Stack Builder)
- Node.js 20.19+ or 22.12+ (LTS) - check with `node -v`
- Git
- VS Code - accept the recommended extensions when prompted

## First-time setup

### 1. Clone

```powershell
cd C:\dev
git clone https://github.com/danielAd0/Credit-Advisory-Application.git
cd Credit-Advisory-Application
```

### 2. Database

Open pgAdmin, connect as `postgres`, open the Query Tool on the `postgres` database.
Run each line **separately** (highlight one line, press F5):

```sql
CREATE USER ca_user WITH PASSWORD 'your-own-password';
```

```sql
CREATE DATABASE credit_advisory OWNER ca_user ENCODING 'UTF8';
```

```sql
CREATE DATABASE credit_advisory_test OWNER ca_user ENCODING 'UTF8';
```

`credit_advisory` is your normal local database. `credit_advisory_test` is used only by the
automated tests and is **wiped before every test** - never keep anything in it.
Leave both empty: Flyway creates the tables.

### 3. Backend

1. In `backend/src/main/resources`, copy `application-local.properties.example`
   and rename the copy to `application-local.properties`.
2. Set `DB_PASSWORD` in it to the password you chose above, and choose your own
   `app.dev.admin-password` (12+ characters). That admin account is created on first start-up.
3. Generate your own **ID-number keys**. The backend will not start without them. In PowerShell,
   run this line **twice** (each run prints a different key):

```powershell
$b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

Add both to `application-local.properties` - the first as the lookup key, the second as the encryption key:

```properties
app.idnumber.lookup-key=<first key>
app.idnumber.encryption-key=<second key>
```

- These keys go **only** in `application-local.properties` (gitignored) - never in the `.example`
  file, a commit, a chat message or a screenshot. Each developer has their own.
- If you ever change them, client ID numbers already in your local database can no longer be read:
  reset your local database. Production keys are created and kept by IT, never by developers.

4. Run from the `backend` folder:

```powershell
.\mvnw.cmd spring-boot:run
```

5. Open http://localhost:8080/actuator/health - you should see `{"status":"UP"}`.
   - `spring-boot:run` always uses the **local** profile (set in `pom.xml`). If you start the
     backend another way (for example VS Code's Run button), set the environment variable
     `SPRING_PROFILES_ACTIVE=local`, otherwise it refuses to start with "No Spring profile is active".
6. Run the automated tests (stop the running backend first). Every test must pass:

```powershell
.\mvnw.cmd clean test
```

### 4. Frontend

Run from the `frontend` folder:

```powershell
npm install
npm run dev
```

Open http://localhost:5173 and sign in with the admin email and password from your
`application-local.properties`. Use `localhost`, not `127.0.0.1`.

## Everyday commands

| Where      | Command                      | What it does                        |
| ---------- | ---------------------------- | ----------------------------------- |
| `backend`  | `.\mvnw.cmd clean compile`   | Compile and check for errors        |
| `backend`  | `.\mvnw.cmd spring-boot:run` | Run the API on port 8080            |
| `backend`  | `.\mvnw.cmd clean test`      | Run the automated API tests         |
| `frontend` | `npm run dev`                | Run the frontend on port 5173       |
| `frontend` | `npm run lint`               | Check code with ESLint              |
| `frontend` | `npm run format`             | Format code with Prettier           |
| `frontend` | `npm run build`              | Type-check and build for production |

## Running in production

For IT. Start with `docs/it-handover.md`: the order to set things up, the database and proxy, the keys,
first start-up, updates and what to do when something goes wrong. This section is the reference it points to.

Nothing secret is in the repository: a server is configured only with environment variables.
If one is missing or a setting is unsafe, the backend **refuses to start** and names the problem.

Build the jar from the `backend` folder:

```powershell
.\mvnw.cmd clean package
```

This runs every test first, then writes `target\credit-advisory-0.0.1-SNAPSHOT.jar`. Run it with Java 21:

```
java -jar credit-advisory-0.0.1-SNAPSHOT.jar
```

| Environment variable       | Required         | What it is                                                                                                           |
| -------------------------- | ---------------- | -------------------------------------------------------------------------------------------------------------------- |
| `SPRING_PROFILES_ACTIVE`   | yes              | always `prod`                                                                                                        |
| `DB_URL`                   | yes              | e.g. `jdbc:postgresql://db-host:5432/credit_advisory?sslmode=verify-full` - a database on another machine **must** use `sslmode=verify-full` (or at least `require`) |
| `DB_USERNAME`              | yes              | database user that owns the schema                                                                                   |
| `DB_PASSWORD`              | yes              | its password                                                                                                         |
| `ID_NUMBER_LOOKUP_KEY`     | yes              | 32 random bytes, base64 - generated by IT                                                                            |
| `ID_NUMBER_ENCRYPTION_KEY` | yes              | another 32 random bytes, base64 - a **different** key                                                                |
| `MFA_ENCRYPTION_KEY`       | yes              | a third 32 random bytes, base64 - encrypts the authenticator secrets; must differ from both ID-number keys          |
| `SERVER_ADDRESS`           | no               | the one address the application listens on; default `127.0.0.1` (proxy on the same machine), otherwise the private address the proxy connects to. Never `0.0.0.0` |
| `MANAGEMENT_PORT`          | no               | port of the health check, default `8081`; must differ from the application port (`8080`)                             |
| `MANAGEMENT_ADDRESS`       | no               | address of the health check, default `127.0.0.1`; set the private address only if the load balancer is on another machine |
| `CORS_ALLOWED_ORIGINS`     | no               | only if the frontend is served from a different address; must be `https://...`                                       |
| `ALLOW_LOCAL_DATABASE`     | no               | `true` only if PostgreSQL runs on the same machine as the app; otherwise a `localhost` `DB_URL` is refused           |
| `FIRST_ADMIN_EMAIL`        | first start only | email of the first administrator                                                                                     |
| `FIRST_ADMIN_PASSWORD`     | first start only | temporary password, 12+ characters; they must change it on first sign-in                                             |

Rules:

- **The ID-number keys must be backed up safely.** If they are lost, every stored ID number becomes unreadable.
  Never change them on a server that already has clients.
- **Every user signs in with an authenticator app** (Google or Microsoft Authenticator) after the password;
  production refuses to start without it. If `MFA_ENCRYPTION_KEY` is lost, nobody can sign in until an
  administrator resets their MFA (`POST /api/users/{id}/mfa-reset`) - and the first administrator then needs
  the database: back this key up like the ID-number keys.
- The first administrator sets up their authenticator at their first sign-in. Do this **straight away** after
  start-up: until an account has an authenticator, whoever knows its password can set one up.
- Serve the application over **HTTPS only**, behind a reverse proxy or load balancer on the private network
  that sends `X-Forwarded-For` and `X-Forwarded-Proto`. The session cookie is `Secure`, so plain HTTP sign-in
  will not work.
- The backend sets its own security headers (CSP, Referrer-Policy, Permissions-Policy, no framing, no caching).
  Whatever serves the **built frontend** (`frontend/dist`) must send these on `index.html` and its files:
  `Content-Security-Policy: default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`,
  `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff` and
  `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()`.
- On the first start-up Flyway creates the tables. With `FIRST_ADMIN_EMAIL` / `FIRST_ADMIN_PASSWORD` set,
  the first administrator is created; it is skipped as soon as an enabled administrator exists.
  **Remove both variables** once that administrator has signed in and chosen their own password.
- Never point a server at a database whose name ends in `_test`, and never run the tests against a
  production database.

### Network checklist

The backend refuses to start if it would listen on every network, if the health check has no port of its
own, or if a database on another machine is reached without encryption. The rest is the server and
firewall, so IT ticks these off before go-live:

1. **Only the reverse proxy is public**, and only on port 443 (HTTPS). Port 80 may only redirect to 443.
   The proxy uses a certificate from a trusted authority and TLS 1.2 or newer.
2. **The application port (`8080`) is reachable only from the proxy.** Firewall: allow the proxy's
   address, deny everyone else. The backend trusts `X-Forwarded-For` from private addresses, so a user who
   could reach `8080` directly could fake their address and get around the sign-in rate limit.
3. **The health port (`MANAGEMENT_PORT`, default `8081`) is reachable only from the load balancer or
   monitoring**, and is never published through the proxy. It answers only `{"status":"UP"}` or
   `{"status":"DOWN"}`: point the load balancer's health check at `http://<app-host>:8081/actuator/health`.
4. **PostgreSQL (`5432`) is reachable only from the application server.** In `pg_hba.conf`, allow only the
   app server's address, with `hostssl` and `scram-sha-256`; never `0.0.0.0/0` or `trust`. The database
   has a TLS certificate, and `DB_URL` uses `sslmode=verify-full`.
5. **No other ports are open** on the application or database server (SSH only from the admin network).
6. **The server can make no outgoing connections except to the database.** The application never calls
   the internet, so blocking it stops stolen data from leaving the easy way.
7. **Backups of the database and the three keys** are stored apart from each other, and a restore is
   tested once before go-live.
8. Check from a machine **outside** the network that only 443 answers (for example
   `Test-NetConnection advisory.example.com -Port 8080` must fail), and from inside that
   `/actuator/health` on port 8080 gives 404 while port 8081 gives `UP`.

## Git workflow

Everyone works directly on `main`. To avoid overwriting each other:

1. **Before you start working**, get the latest code:

```powershell
git pull
```

2. **Commit small and often** - one finished piece of work per commit, with a clear message.

3. **Before you push**, pull again in case someone else pushed in the meantime:

```powershell
git add backend/src frontend/src docs
git status
git commit -m "Describe what you did"
git pull
git push
```

4. If `git pull` reports a **CONFLICT**, stop and sort it out together - do not force push.
5. Never push code that does not compile or build, or backend code whose tests fail.
6. Check every file in `git status` before committing. Stage folders you worked in rather than `git add .`.

## Team rules

- Never change the database by hand. Every schema change is a new Flyway migration in
  `backend/src/main/resources/db/migration`.
- Never edit a migration that has already been merged - write a new one.
- Never commit `application-local.properties`, passwords, keys, or any `.xlsx` / `.csv` client data.
- Run `npm run lint` (frontend) and `.\mvnw.cmd clean test` (backend) before you push.
- Never test with the real workbook or real client data. Full ID numbers never go in URLs, logs or audit logs.
- Never use `git push --force`.

## Known pins

- TypeScript is pinned to `~6.0.3` because `typescript-eslint` does not support TypeScript 7 yet.
  Only upgrade once `typescript-eslint` lists TS 7 as supported.

## Documents

- `docs/it-handover.md` - installing and running the application, for IT
- `docs/api-auth-consultants-clients.md` - API contract between backend and frontend
- `docs/SRS-v1.docx` - requirements specification and technical architecture
- `docs/spreadsheet-business-breakdown.docx` - analysis of the original Excel workbook
