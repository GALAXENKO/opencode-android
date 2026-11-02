const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
const off = parseInt(process.argv[3], 10);
const len = parseInt(process.argv[4] || "160", 10);
const s = Math.max(0, off - 64);
for (let i = s; i < off + len; i += 16) {
  let hex = [], asc = [];
  for (let j = 0; j < 16; j++) {
    const b = buf[i + j] ?? 0;
    hex.push(b.toString(16).padStart(2, "0"));
    asc.push(b >= 32 && b < 127 ? String.fromCharCode(b) : ".");
  }
  console.log(i.toString().padStart(10), hex.join(" "), " " + asc.join(""));
}
