// Downloads a web-UI asset with a valid session cookie and greps it.
// usage: node assetgrep.js <port> <password> <assetPath> <needle...>
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const asset = process.argv[4];
const needles = process.argv.slice(5);

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}${asset}`, { headers: { cookie } });
  const t = await r.text();
  console.log("status", r.status, "len", t.length);
  for (const needle of needles) {
    let i = -1;
    let count = 0;
    while ((i = t.indexOf(needle, i + 1)) >= 0) {
      count++;
      if (count <= 3) {
        console.log(`--- ${needle} @${i}`);
        console.log(t.slice(Math.max(0, i - 220), i + 220).replace(/\s+/g, " "));
      }
    }
    console.log(`=== ${needle}: ${count} hits`);
  }
})().catch((e) => console.log("ERR", e.message));
