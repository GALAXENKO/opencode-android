// Lists openapi paths matching a regex.
// usage: node ocpaths.js <port> <password> [regex]
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const re = new RegExp(process.argv[4] || ".", "i");

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}/openapi.json`, { headers: { cookie } });
  const j = await r.json();
  for (const [p, ops] of Object.entries(j.paths)) {
    if (!re.test(p)) continue;
    console.log(p, Object.keys(ops).join(","));
  }
})().catch((e) => console.log("ERR", e.message));
