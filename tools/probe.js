const crypto = require("crypto");
const pw = process.env.OCPW || "testpass123";
const port = Number(process.env.OCPORT || 4112);
const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;
const base = `http://127.0.0.1:${port}`;

async function get(path, headers = {}) {
  try {
    const r = await fetch(base + path, { headers: { cookie, ...headers }, redirect: "manual" });
    const text = await r.text();
    return { path, status: r.status, type: r.headers.get("content-type"), len: text.length, head: text.slice(0, 300).replace(/\s+/g, " ") };
  } catch (e) {
    return { path, error: String(e) };
  }
}

(async () => {
  const paths = process.argv.slice(2);
  for (const p of paths) {
    const r = await get(p);
    console.log(JSON.stringify(r));
  }
})();
