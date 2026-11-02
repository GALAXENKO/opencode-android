const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
const start = Number(process.argv[3]);
const end = Number(process.argv[4]);
let out = "";
for (let i = start; i < Math.min(end, buf.length); i++) {
  const b = buf[i];
  out += b >= 32 && b < 127 ? String.fromCharCode(b) : (b === 0 ? "\n" : "\u00b7");
}
process.stdout.write(out);
