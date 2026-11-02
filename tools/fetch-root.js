const crypto = require("crypto");
const pw = process.env.OCPW || "testpass123";
const port = Number(process.env.OCPORT || 4212);
const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;
const base = `http://127.0.0.1:${port}`;
(async () => {
  const r = await fetch(base + "/", { headers: { cookie } });
  const html = await r.text();
  console.log(html);
})();
