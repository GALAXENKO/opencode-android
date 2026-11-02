const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
function ctx(off, before, after) {
  const s = Math.max(0, off - before), e = Math.min(buf.length, off + after);
  let out = "";
  for (let i = s; i < e; i++) {
    const b = buf[i];
    out += b >= 32 && b < 127 ? String.fromCharCode(b) : (b === 0 ? "|" : ".");
  }
  return out;
}
const needles = process.argv.slice(3);
for (const n of needles) {
  const b = Buffer.from(n, "latin1");
  const hits = [];
  let i = buf.indexOf(b);
  while (i >= 0) { hits.push(i); i = buf.indexOf(b, i + 1); }
  console.log("=== " + n + " (" + hits.length + ")");
  for (const h of hits.slice(0, 6)) console.log("  @" + h + " " + JSON.stringify(ctx(h, 100, 200)));
}
