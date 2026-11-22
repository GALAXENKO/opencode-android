// Lists sessions of a running opencode server.
// usage: node ocsessions.js <port> <password>
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}/api/session`, { headers: { cookie } });
  const t = await r.text();
  console.log(r.status, t.slice(0, 2000));
})();
