const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
// find NUL-bounded exact matches
const needles = process.argv.slice(3);
for (const n of needles) {
  const b = Buffer.from(n, "latin1");
  let i = buf.indexOf(b);
  const hits = [];
  while (i >= 0) {
    const prev = i === 0 ? 0 : buf[i - 1];
    const next = buf[i + b.length];
    if (prev === 0 && next === 0) hits.push(i);
    i = buf.indexOf(b, i + 1);
  }
  console.log("=== " + JSON.stringify(n) + " standalone: " + hits.length + " -> " + hits.join(","));
}
