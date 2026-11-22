// Scans every script asset of the served web UI for a needle.
// usage: node scanassets.js <port> <password> <needle>
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const needle = process.argv[4];

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

async function get(path) {
  const r = await fetch(`http://127.0.0.1:${port}${path}`, { headers: { cookie } });
  if (!r.ok) return null;
  return r.text();
}

(async () => {
  const html = await get("/");
  const assets = [...html.matchAll(/"(\/_assets\/[^"]+\.js)"/g)].map((m) => m[1]);
  const seen = new Set(assets);
  const queue = [...assets];
  let hits = 0;
  while (queue.length) {
    const asset = queue.shift();
    const body = await get(asset);
    if (!body) continue;
    const i = body.indexOf(needle);
    if (i >= 0) {
      hits++;
      console.log(`\n### ${asset} @${i}`);
      console.log(body.slice(Math.max(0, i - 400), i + 500).replace(/\s+/g, " "));
    }
    // follow nested chunks so lazily-loaded code is covered too
    for (const m of body.matchAll(/"(\/_assets\/[^"]+\.js)"/g)) {
      if (!seen.has(m[1])) {
        seen.add(m[1]);
        queue.push(m[1]);
      }
    }
  }
  console.log(`\nscanned ${seen.size} assets, ${hits} hits for ${needle}`);
})().catch((e) => console.log("ERR", e.message));
