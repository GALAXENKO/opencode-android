const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
function dump(off, before = 400, after = 400) {
  const s = Math.max(0, off - before), e = Math.min(buf.length, off + after);
  let out = "";
  for (let i = s; i < e; i++) {
    const b = buf[i];
    out += b >= 32 && b < 127 ? String.fromCharCode(b) : (b === 0 ? "|" : ".");
  }
  return "@" + off + "\n" + out;
}
for (const a of process.argv.slice(3)) console.log(dump(Number(a)) + "\n");
