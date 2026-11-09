# Credit Advisory - IT handover

For the IT team that installs and runs the Credit Advisory Application. It says **what to set up, in
which order, and what to do when something goes wrong**. The settings themselves are listed once, in the
README section "Running in production" - this note refers to it rather than repeating it.

The application holds clients' names, phone numbers, email addresses and South African ID numbers, so it
falls under POPIA. Every step below is written with that in mind.

Questions: the development team - Ntiyiso (Dan) Bila, backend; Sifiso, import and reconciliation;
Jack, frontend.

---

## 1. What runs where

```
 browser ──HTTPS 443──> reverse proxy ──HTTP 8080──> application (Java 21) ──TLS 5432──> PostgreSQL 16
                        (serves the frontend,         (health check on 8081,
                         forwards /api/*)              load balancer only)
```

| Part | What it is | Who may reach it |
| --- | --- | --- |
| Reverse proxy | nginx, IIS, a load balancer - anything that does HTTPS. Serves the built frontend (`frontend/dist`) and forwards `/api/` to the application | everyone, port 443 only |
| Application | one jar, `credit-advisory-0.0.1-SNAPSHOT.jar`, Java 21 | the proxy only (8080); the load balancer only (8081) |
| Database | PostgreSQL 16, one database `credit_advisory` | the application server only |

The application never connects to the internet. Frontend and API are served from **the same https
address**, so no CORS set-up is needed.

## 2. What IT provides

- A server for the application with **Java 21** (Temurin or any OpenJDK 21), and a dedicated user account
  that is not an administrator.
- **PostgreSQL 16**, with a TLS certificate. Same server or another one - another one is better.
- A **reverse proxy** with a certificate from a trusted authority (not self-signed) for the address users
  will type, e.g. `advisory.example.co.za`.
- A place to keep **secrets** (a password manager or vault) that only named IT staff can open.
- **Backups** for the database and, separately, for the three keys (section 4).

## 3. Database

Run as the PostgreSQL superuser. Choose a long random password; it goes into `DB_PASSWORD` later.

```sql
CREATE ROLE ca_app LOGIN PASSWORD 'choose-a-long-random-password';
CREATE DATABASE credit_advisory OWNER ca_app;
REVOKE ALL ON DATABASE credit_advisory FROM PUBLIC;
```

The application creates and updates its own tables (Flyway) on every start-up - nobody runs SQL scripts
by hand.

`postgresql.conf`:

```
listen_addresses = '10.0.0.6'          # the database server's private address, not '*'
ssl = on
password_encryption = scram-sha-256
```

`pg_hba.conf` - the application server's address only, encrypted, never `trust`:

```
hostssl  credit_advisory  ca_app  10.0.0.5/32  scram-sha-256
```

`DB_URL` then ends in `?sslmode=verify-full`, e.g.
`jdbc:postgresql://db.internal:5432/credit_advisory?sslmode=verify-full`. The application refuses to start
if a database on another machine is reached without `sslmode=verify-full`, `verify-ca` or `require`.

**Never** name the production database anything ending in `_test`: the automated tests wipe such
databases, and the application refuses to start against one.

## 4. The three keys

| Variable | Protects | If it is lost |
| --- | --- | --- |
| `ID_NUMBER_ENCRYPTION_KEY` | the stored ID numbers (AES-256) | **every ID number is unreadable for good.** No way back except the backup of the key |
| `ID_NUMBER_LOOKUP_KEY` | finding a client by ID number, and the duplicate check | lookups and duplicate checks stop working. Same - only the backup helps |
| `MFA_ENCRYPTION_KEY` | the authenticator-app secrets | nobody can finish signing in until their two-step sign-in is reset (section 9) |

Each is 32 random bytes written as base64 (44 characters, ending in `=`). Make **three different** keys -
the application refuses to start if two are the same.

Linux:

```bash
openssl rand -base64 32
```

Windows PowerShell:

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

Rules:

- IT makes them, IT keeps them. **Developers never see production keys** - they have their own for their
  laptops, and the test keys in the repository are for fake test data only.
- Never send a key by email, chat or ticket, and never put one in a screenshot.
- Store each in the vault **and** in a second, offline place (e.g. a sealed envelope in a safe, or an
  encrypted USB stick in a different building). Keep this apart from the database backups: a stolen
  backup must not come with its key.
- **Never change `ID_NUMBER_*` keys on a server that has clients.** Changing a key is not supported yet;
  the stored numbers would no longer match it.

## 5. Install

### 5.1 The jar

The development team hands over a jar built from a commit on `main` whose automated tests all passed
(`.\mvnw.cmd clean package` in `backend` runs them first; it needs the developers' test database, which is
why IT does not build it). Check that the commit is the one agreed.

Put it in `/opt/credit-advisory/credit-advisory.jar` (or `C:\Program Files\CreditAdvisory\` on Windows),
owned by an administrator, readable by the service account, not writable by it.

### 5.2 The settings

Every setting is an environment variable - the full list is the table in README "Running in production".
Put them in one file only the service account can read:

Linux - `/etc/credit-advisory/env`, owner `root`, group `credit-advisory`, mode `640`:

```
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://db.internal:5432/credit_advisory?sslmode=verify-full
DB_USERNAME=ca_app
DB_PASSWORD=...
ID_NUMBER_LOOKUP_KEY=...
ID_NUMBER_ENCRYPTION_KEY=...
MFA_ENCRYPTION_KEY=...
SERVER_ADDRESS=10.0.0.5
FIRST_ADMIN_EMAIL=...
FIRST_ADMIN_PASSWORD=...
```

`SERVER_ADDRESS` is the private address the proxy connects to. Leave it out if the proxy runs on the same
machine (the default `127.0.0.1` is right then). The health check listens on `127.0.0.1:8081` unless
`MANAGEMENT_ADDRESS` / `MANAGEMENT_PORT` say otherwise - set `MANAGEMENT_ADDRESS` to the private address only
if the load balancer is on another machine.

On Windows: a service wrapper with the same variables set for the service only, under a dedicated account.

### 5.3 Run it as a service

Linux, `/etc/systemd/system/credit-advisory.service`:

```ini
[Unit]
Description=Credit Advisory
After=network-online.target
Wants=network-online.target

[Service]
User=credit-advisory
Group=credit-advisory
EnvironmentFile=/etc/credit-advisory/env
ExecStart=/usr/bin/java -jar /opt/credit-advisory/credit-advisory.jar
Restart=on-failure
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now credit-advisory
journalctl -u credit-advisory -f
```

If a setting is missing or unsafe, it stops at once with `Refusing to start in production:` and a list of
what to fix, by variable name (never the values). Fix those and start again.

### 5.4 The frontend

The development team hands over the built `frontend/dist` folder (`npm ci` then `npm run build` in
`frontend`). It is plain files: put them where the proxy serves them, e.g. `/var/www/credit-advisory`.

### 5.5 The reverse proxy

An nginx example - any proxy works if it does the same five things: HTTPS only, the security headers on
the frontend files, `/api/` forwarded with `X-Forwarded-For` and `X-Forwarded-Proto`, uploads up to 11 MB,
and every other address answered with `index.html`.

```nginx
server {
    listen 80;
    server_name advisory.example.co.za;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    http2 on;                              # nginx older than 1.25.1: "listen 443 ssl http2;" instead
    server_name advisory.example.co.za;

    ssl_certificate     /etc/ssl/advisory/fullchain.pem;
    ssl_certificate_key /etc/ssl/advisory/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    server_tokens off;

    # The API: the application sets its own security headers (HSTS included).
    location /api/ {
        proxy_pass http://10.0.0.5:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 11m;          # weekly register upload: 10 MB file
    }

    # The frontend files.
    location / {
        root /var/www/credit-advisory;
        try_files $uri /index.html;
        add_header Content-Security-Policy "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'" always;
        add_header Referrer-Policy "no-referrer" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
        add_header Strict-Transport-Security "max-age=31536000" always;
    }
}
```

Do **not** forward `/actuator/` through the proxy: the health check is for the load balancer only, on
port 8081 (README, "Network checklist").

The proxy must be on a private address (10.x, 172.16-31.x, 192.168.x) or the same machine: the application
only believes `X-Forwarded-For` from such addresses, and uses it for the sign-in rate limit.

## 6. First start-up

1. Start the service with `FIRST_ADMIN_EMAIL` and `FIRST_ADMIN_PASSWORD` set (12+ characters). The tables
   are created and this one administrator account is made.
2. **Straight away**, that person signs in at the https address. They are asked to set up an authenticator
   app (Microsoft or Google Authenticator), save their 10 recovery codes somewhere safe, then choose their
   own password. Until they have done this, anyone who knows the temporary password could set up an
   authenticator in their place.
3. Remove `FIRST_ADMIN_EMAIL` and `FIRST_ADMIN_PASSWORD` from the settings file and restart.
4. The administrator creates the other accounts in the application. Each new user also sets up their
   authenticator and their own password at their first sign-in.

## 7. Before go-live

- Work through the **Network checklist** in the README (8 items: what is public, firewall rules,
  `pg_hba.conf`, no outgoing connections, backups, testing from outside).
- From the application server: `curl http://127.0.0.1:8081/actuator/health` answers `{"status":"UP"}`.
- From outside the network: only port 443 answers; `https://<address>/actuator/health` is a 404.
- Sign in on a phone and on a laptop; make sure the browser shows a valid certificate.
- Restore the latest database backup to a separate, non-production server once, and start a copy of the
  application against it with the **same keys**: clients' full ID numbers must show. This is the only way
  to know the backups and the key copies really work.

## 8. Running it

**Health:** the load balancer or monitoring checks `http://<app-host>:8081/actuator/health` - `UP` or
`DOWN`, nothing more.

**Logs:** the application writes to standard output (`journalctl -u credit-advisory`). It never logs ID
numbers, passwords, codes or keys. Keep logs for as long as company policy says.

**Backups:** a nightly `pg_dump` of `credit_advisory`, encrypted, kept off the database server. The dumps
hold names, phone numbers and email addresses in readable form (ID numbers stay encrypted), so treat them
like the live data. Retention: as the company's POPIA policy says.

**Updates:**
1. Back up the database.
2. Stop the service, replace the jar (and `dist`, if the frontend changed), start the service.
3. Database changes run by themselves at start-up and are listed in the log (`Migrating schema ...`).
4. Going back to an older version means restoring the backup from step 1 **and** the older jar: database
   changes only go forward.

Everybody is signed out by a restart. Users are also signed out after 30 minutes without activity.

## 9. When something goes wrong

| Situation | What to do |
| --- | --- |
| A user is locked out (5 wrong passwords or codes) | it opens again by itself after 15 minutes, or an administrator: Consultants > Manage > Unlock account |
| A user forgot their password | an administrator: Consultants > Manage > Reset password. Hand the temporary password over in person |
| A user lost their phone and their recovery codes | an administrator: Consultants > Manage > Reset two-step sign-in. **Check who you are talking to first** - this is how an attacker would try to get in |
| The same, for a **manager or administrator** account | there is no screen for these accounts yet; the server supports it (`docs/api-admin.md`, `/api/users/{id}/...`). Ask the development team |
| 20 failed sign-ins within 5 minutes from one network address | that address gets "too many attempts" for a few minutes, then works again by itself |
| The application will not start: `Refusing to start in production` | the message names every setting to fix (section 5.2) |
| The application will not start: database errors | is PostgreSQL running, does the firewall allow the app server, do `DB_URL`, user and password match, is the certificate valid for `sslmode=verify-full` |
| `MFA_ENCRYPTION_KEY` lost, no backup | set a new key; then, as the database superuser, `DELETE FROM user_mfa;` and restart. Everyone sets up their authenticator again at their next sign-in. Tell users first |
| An ID-number key lost, no backup | stop the application and call the development team. The stored ID numbers cannot be recovered |
| A key may have leaked (seen by someone who should not have) | treat it as a security incident: tell the company's information officer and the development team the same day. Changing a key needs the development team |
| A laptop or phone with access was stolen | for a consultant, an administrator sets them to Suspended (Consultants > Manage > Status) or resets their password and two-step sign-in - each signs out all their sessions at once. For a manager or administrator, ask the development team |

The audit log (administrators: Audit log in the menu) shows who did what and when - lockouts, blocked
sign-in addresses, wrong passwords when showing an ID number, every full ID number viewed, imports,
reports exported, changes to clients and accounts. It cannot be edited, not even by an administrator.
