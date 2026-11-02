const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
const off = parseInt(process.argv[3], 10);
const before = parseInt(process.argv[4] || "600", 10);
const after = parseInt(process.argv[5] || "2500", 10);
const s = Math.max(0, off - before), e = Math.min(buf.length, off + after);
let out = "";
for (let i = s; i < e; i++) {
  const b = buf[i];
  out += b >= 32 && b < 127 ? String.fromCharCode(b) : (b === 0 ? "\n" : ".");
}
console.log(out);
