const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
function ctx(off, before = 120, after = 120) {
  const s = Math.max(0, off - before), e = Math.min(buf.length, off + after);
  let out = "";
  for (let i = s; i < e; i++) {
    const b = buf[i];
    out += b >= 32 && b < 127 ? String.fromCharCode(b) : ".";
  }
  return out;
}
function findAll(needle) {
  const b = Buffer.from(needle, "latin1");
  const hits = [];
  let i = buf.indexOf(b);
  while (i >= 0) { hits.push(i); i = buf.indexOf(b, i + 1); }
  return hits;
}
for (const n of ["/etc/resolv.conf", "/etc/ssl/cert.pem", "/etc/ssl/certs/ca-certificates.crt", "HTTPS_PROXY", "https_proxy", "NO_PROXY", "no_proxy"]) {
  const hits = findAll(n);
  console.log("=== " + n + " (" + hits.length + ")");
  for (const h of hits.slice(0, 8)) console.log("  @" + h + "  " + JSON.stringify(ctx(h)));
}
