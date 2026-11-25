// Fake Microsoft 365 for LOCAL testing of the login emails. Never use it in production.
// It answers the two calls the backend makes (sign-in token + sendMail) and saves every
// email as an .html file instead of sending it. No packages needed: Node 18 or newer.
//
//   node tools/fake-graph-mail.mjs
//   then open http://localhost:4545 to see the emails "sent".
//
// Optional:
//   PORT=4545                          port to listen on
//   OUTBOX=./mail-outbox               folder the emails are saved in
//   FAIL=sipho@example.test            addresses that fail permanently (HTTP 400, like a bad mailbox)
//   DOWN=naledi@example.test           addresses that fail temporarily (HTTP 503), to try Retry
//   THROTTLE=1                         answer every send with HTTP 429 (too many requests)
import { createServer } from 'node:http';
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const port = Number(process.env.PORT ?? 4545);
const outbox = resolve(process.env.OUTBOX ?? './mail-outbox');
const list = (v) =>
    new Set(
        (v ?? '')
            .split(',')
            .map((s) => s.trim().toLowerCase())
            .filter(Boolean),
    );
const fail = list(process.env.FAIL);
const down = list(process.env.DOWN);
const throttle = process.env.THROTTLE === '1';
mkdirSync(outbox, { recursive: true });

const read = (req) =>
    new Promise((ok) => {
        let body = '';
        req.on('data', (c) => (body += c));
        req.on('end', () => ok(body));
    });

const send = (res, status, body, type = 'application/json') => {
    res.writeHead(status, { 'Content-Type': type });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
};

createServer(async (req, res) => {
    const url = new URL(req.url, `http://localhost:${port}`);

    // 1. Sign-in: POST /{tenant}/oauth2/v2.0/token
    if (req.method === 'POST' && /^\/[^/]+\/oauth2\/v2\.0\/token$/.test(url.pathname)) {
        const form = new URLSearchParams(await read(req));
        if (form.get('grant_type') !== 'client_credentials')
            return send(res, 400, { error: 'unsupported_grant_type' });
        console.log(`token issued to client ${form.get('client_id')}`);
        return send(res, 200, {
            token_type: 'Bearer',
            expires_in: 3599,
            access_token: 'local-test-token',
        });
    }

    // 2. Send: POST /v1.0/users/{sender}/sendMail
    const m = url.pathname.match(/^\/v1\.0\/users\/([^/]+)\/sendMail$/);
    if (req.method === 'POST' && m) {
        if (req.headers.authorization !== 'Bearer local-test-token')
            return send(res, 401, { error: { code: 'InvalidAuthenticationToken' } });
        const mail = JSON.parse(await read(req)).message;
        const to = mail.toRecipients[0].emailAddress.address.toLowerCase();
        if (throttle) return send(res, 429, { error: { code: 'ApplicationThrottled' } });
        if (fail.has(to)) {
            console.log(`REFUSED ${to} (permanent)`);
            return send(res, 400, { error: { code: 'ErrorInvalidRecipients' } });
        }
        if (down.has(to)) {
            console.log(`REFUSED ${to} (temporary)`);
            return send(res, 503, { error: { code: 'ServiceUnavailable' } });
        }
        const file = `${new Date().toISOString().replace(/[:.]/g, '-')}_${to.replace(/[^a-z0-9@._-]/g, '_')}.html`;
        writeFileSync(join(outbox, file), mail.body.content);
        console.log(`sent "${mail.subject}" from ${decodeURIComponent(m[1])} to ${to} -> ${file}`);
        res.writeHead(202);
        return res.end();
    }

    // 3. Browse the outbox: GET /  and  GET /mail/{file}
    if (req.method === 'GET' && url.pathname === '/') {
        const files = readdirSync(outbox)
            .filter((f) => f.endsWith('.html'))
            .sort()
            .reverse();
        const rows = files
            .map((f) => `<li><a href="/mail/${encodeURIComponent(f)}">${f}</a></li>`)
            .join('');
        return send(
            res,
            200,
            `<h1>Local outbox (${files.length})</h1><ul>${rows}</ul>`,
            'text/html; charset=utf-8',
        );
    }
    if (req.method === 'GET' && url.pathname.startsWith('/mail/')) {
        const name = decodeURIComponent(url.pathname.slice(6));
        if (name.includes('/') || name.includes('\\') || !name.endsWith('.html'))
            return send(res, 404, 'Not found', 'text/plain');
        try {
            return send(res, 200, readFileSync(join(outbox, name), 'utf8'), 'text/html; charset=utf-8');
        } catch {
            return send(res, 404, 'Not found', 'text/plain');
        }
    }
    send(res, 404, { error: { code: 'NotFound' } });
}).listen(port, () =>
    console.log(`Fake Microsoft 365 on http://localhost:${port} - emails saved in ${outbox}`),
);
