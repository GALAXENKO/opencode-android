const crypto = require("crypto");
const pw = process.env.OCPW || "testpass123";
const port = Number(process.env.OCPORT || 4212);
const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;
const base = `http://127.0.0.1:${port}`;

async function call(method, path, body) {
  const init = { method, headers: { cookie, "content-type": "application/json" } };
  if (body !== undefined) init.body = JSON.stringify(body);
  try {
    const r = await fetch(base + path, init);
    const t = await r.text();
    return { method, path, status: r.status, body: t.slice(0, 600) };
  } catch (e) {
    return { method, path, error: String(e) };
  }
}

(async () => {
  const args = process.argv.slice(2);
  for (const a of args) {
    const [method, path, body] = a.split("|");
    const r = await call(method, path, body ? JSON.parse(body) : undefined);
    console.log(JSON.stringify(r));
  }
})();
