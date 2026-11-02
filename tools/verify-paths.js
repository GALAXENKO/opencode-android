// Verify that no standalone (NUL-terminated) /etc paths remain in the CLI binary.
const fs = require("fs");
const file = process.argv[2];
const buf = fs.readFileSync(file);
function countStandalone(s) {
  const needle = Buffer.from(s, "latin1");
  let i = 0, n = 0;
  for (;;) {
    const off = buf.indexOf(needle, i);
    if (off < 0) break;
    i = off + 1;
    const before = off > 0 ? buf[off - 1] : 0;
    const after = off + needle.length < buf.length ? buf[off + needle.length] : 0;
    if (before === 0 && after === 0) n++;
  }
  return n;
}
for (const s of ["/etc/resolv.conf", "/etc/hosts", "/etc/passwd", "/etc/ssl", "/bin/sh", "/tmp"]) {
  console.log(`${s}: standalone=${countStandalone(s)}`);
}
console.log(`size=${buf.length}`);
