// Report which path strings the musl loader currently contains.
const fs = require("fs");
const file = process.argv[2];
if (!file) {
  console.error("usage: node check-musl.js <ld-musl-aarch64.so>");
  process.exit(1);
}
const buf = fs.readFileSync(file);
const names = ["/etc/hosts", "/etc/resolv.conf", "hosts", "resolv.conf"];
for (const n of names) {
  let count = 0;
  let offset = 0;
  const needle = Buffer.from(n + "\0", "latin1");
  for (;;) {
    const at = buf.indexOf(needle, offset);
    if (at < 0) break;
    count++;
    offset = at + needle.length;
  }
  console.log(`${n}: ${count}`);
}
console.log(`size: ${buf.length}`);
