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
git checkout develop
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

### 3. Backend

1. In `backend/src/main/resources`, copy `application-local.properties.example`
   and rename the copy to `application-local.properties`.
2. Set `DB_PASSWORD` in it to the password you chose above.
3. Run from the `backend` folder:

```powershell
.\mvnw.cmd spring-boot:run
```

4. Open http://localhost:8080/actuator/health - log in with username `user` and the
   generated password printed in the console. You should see `"status":"UP"`.

### 4. Frontend

Run from the `frontend` folder:

```powershell
npm install
npm run dev
```

Open http://localhost:5173

## Everyday commands

| Where      | Command                      | What it does                        |
| ---------- | ---------------------------- | ----------------------------------- |
| `backend`  | `.\mvnw.cmd clean compile`   | Compile and check for errors        |
| `backend`  | `.\mvnw.cmd spring-boot:run` | Run the API on port 8080            |
| `frontend` | `npm run dev`                | Run the frontend on port 5173       |
| `frontend` | `npm run lint`               | Check code with ESLint              |
| `frontend` | `npm run format`             | Format code with Prettier           |
| `frontend` | `npm run build`              | Type-check and build for production |

## Git workflow

- `main` - released code only. Never commit directly.
- `develop` - integration branch. Never commit directly.
- Create every piece of work on a `feature/<short-name>` branch from an up-to-date `develop`:

```powershell
git checkout develop
git pull
git checkout -b feature/<short-name>
```

- Open a pull request into `develop` and merge with **Squash and merge**.
- `develop` into `main` uses **Create a merge commit** (never squash).

## Team rules

- Never change the database by hand. Every schema change is a new Flyway migration in
  `backend/src/main/resources/db/migration`.
- Never edit a migration that has already been merged - write a new one.
- Never commit `application-local.properties`, passwords, keys, or any `.xlsx` / `.csv` client data.
- Run `npm run lint` and `.\mvnw.cmd clean compile` before opening a pull request.

## Known pins

- TypeScript is pinned to `~6.0.3` because `typescript-eslint` does not support TypeScript 7 yet.
  Only upgrade once `typescript-eslint` lists TS 7 as supported.

## Documents

- `docs/SRS-v1.docx` - requirements specification and technical architecture
- `docs/spreadsheet-business-breakdown.docx` - analysis of the original Excel workbook
