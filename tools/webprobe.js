// Fetches the served web UI with a valid session cookie and greps its assets.
// usage: node webprobe.js <port> <password> [urlPath]
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const path = process.argv[4] || "/";

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}${path}`, { headers: { cookie } });
  const t = await r.text();
  console.log("status", r.status, "len", t.length);
  const links = [...t.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]);
  console.log("links:", JSON.stringify(links.slice(0, 30)));
  if (path !== "/") {
    for (const needle of ["showOpenFilePicker", "type=\"file\"", "input type=file"]) {
      console.log(needle, "=>", t.includes(needle));
    }
  } else {
    console.log(t.slice(0, 800));
  }
})().catch((e) => console.log("ERR", e.message));
