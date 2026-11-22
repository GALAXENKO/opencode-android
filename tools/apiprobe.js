// GET a server API path with a valid session cookie.
// usage: node apiprobe.js <port> <password> <path>
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const path = process.argv[4];

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}${path}`, { headers: { cookie, accept: "application/json" } });
  const t = await r.text();
  console.log("status", r.status, "len", t.length);
  try {
    const j = JSON.parse(t);
    console.log(JSON.stringify(j, null, 1).slice(0, 4000));
  } catch {
    console.log(t.slice(0, 2000));
  }
})().catch((e) => console.log("ERR", e.message));
