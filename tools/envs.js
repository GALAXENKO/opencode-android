const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
const re = /OPENCODE_[A-Z0-9_]{2,40}/g;
const set = new Map();
const s = buf.toString("latin1");
let m;
while ((m = re.exec(s))) {
  set.set(m[0], (set.get(m[0]) || 0) + 1);
}
const arr = [...set.entries()].sort((a, b) => a[0].localeCompare(b[0]));
console.log(arr.length);
for (const [k, v] of arr) console.log(k + "  x" + v);
