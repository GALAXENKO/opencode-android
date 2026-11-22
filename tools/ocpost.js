// POST/GET a server API path with a valid session cookie.
// usage: node ocpost.js <port> <password> <method> <path> [jsonBodyFileOrInline]
const crypto = require("crypto");
const fs = require("fs");
const port = process.argv[2];
const pw = process.argv[3];
const method = (process.argv[4] || "GET").toUpperCase();
const path = process.argv[5];
const raw = process.argv[6] || "";
const body = raw ? (fs.existsSync(raw) ? fs.readFileSync(raw, "utf8") : raw) : "";

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}${path}`, {
    method,
    headers: { cookie, accept: "application/json", "content-type": "application/json" },
    body: body || undefined,
    signal: AbortSignal.timeout(300000),
  });
  const t = await r.text();
  console.log("status", r.status, "len", t.length);
  try {
    console.log(JSON.stringify(JSON.parse(t), null, 1).slice(0, 6000));
  } catch {
    console.log(t.slice(0, 3000));
  }
})().catch((e) => console.log("ERR", e.message));
