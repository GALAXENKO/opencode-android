const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
const off = parseInt(process.argv[3], 10);
const len = parseInt(process.argv[4] || "1200", 10);
let out = "";
for (let i = off; i < Math.min(buf.length, off + len); i++) {
  const b = buf[i];
  out += b === 10 ? "\\n" : b === 9 ? "\\t" : b >= 32 && b < 127 ? String.fromCharCode(b) : b === 0 ? "." : "?";
}
console.log(out);
