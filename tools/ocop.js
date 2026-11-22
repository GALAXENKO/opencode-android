// Prints the openapi operation object for one path+method.
// usage: node ocop.js <port> <password> <path> [method]
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const target = process.argv[4];
const method = (process.argv[5] || "post").toLowerCase();

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}/openapi.json`, { headers: { cookie } });
  const j = await r.json();
  const op = j.paths[target] && j.paths[target][method];
  if (!op) return console.log("no such op", Object.keys(j.paths[target] || {}));
  console.log(JSON.stringify(op, null, 1));
})().catch((e) => console.log("ERR", e.message));
