# Frontend <-> backend contract (draft)

The React app needs these from the Spring Boot backend. Mock mode
(`VITE_USE_MOCK=true`) lets the front end be built before any of it exists.

## Authentication (session cookie, no tokens in the browser)

| Call | Purpose |
|------|---------|
| `GET /login` | Server-rendered sign-in page (already built) |
| `POST /login` | Form post from that page (already built) |
| `POST /logout` | Needs the CSRF header; the app then goes to `/login?logout` |
| `GET /api/auth/me` | **New.** 200 with the session below, or 401 when signed out |

`GET /api/auth/me` response:

```json
{
  "username": "string",
  "role": "ADMIN | MANAGER | CONSULTANT",
  "mustChangePassword": false,
  "csrf": { "headerName": "X-CSRF-TOKEN", "token": "..." }
}
```

All `/api/**` calls from a signed-out browser must return **401 JSON**, not a
redirect to the sign-in page. Every POST/PUT/PATCH/DELETE must send the CSRF header.
Responses with the token must be `Cache-Control: no-store`.

## Data (provisional)

| Call | Returns |
|------|---------|
| `GET /api/clients?q=` | `ClientSummary[]` (ID number always masked) |
| `GET /api/consultants` | `ConsultantSummary[]` (managers and admins only) |

Shapes are in `frontend/src/api/types.ts`. The backend must enforce roles on every
endpoint; hiding a menu item in the UI is not security.

## Rules for front-end code

- No client data in `localStorage`, `sessionStorage`, URLs or console logs.
- Show masked ID numbers in lists; reveal a full ID only on an explicit action that the backend audits.
- Show generic error messages, never raw server responses.
- Mock data must be invented.
