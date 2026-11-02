// Patch the musl dynamic loader so that it looks up `hosts` and `resolv.conf`
// relative to the current working directory instead of the (non-existent on
// Android) `/etc` directory. Strings are plain NUL-terminated C strings and the
// replacements are shorter, so the remaining bytes are zero-filled.
const fs = require("fs");

const file = process.argv[2];
if (!file) {
  console.error("usage: node patch-musl.js <ld-musl-aarch64.so>");
  process.exit(1);
}

const patches = [
  ["/etc/hosts", "hosts"],
  ["/etc/resolv.conf", "resolv.conf"],
];

const buf = fs.readFileSync(file);
let changed = 0;

for (const [from, to] of patches) {
  const needle = Buffer.from(from + "\0", "latin1");
  const replacement = Buffer.concat([
    Buffer.from(to, "latin1"),
    Buffer.alloc(from.length - to.length + 1),
  ]);
  let count = 0;
  let offset = 0;
  for (;;) {
    const at = buf.indexOf(needle, offset);
    if (at < 0) break;
    replacement.copy(buf, at);
    count++;
    offset = at + needle.length;
  }
  if (count === 0) {
    console.error(`MISSING ${from}`);
    process.exit(2);
  }
  changed += count;
  console.log(`${from} -> ${to}: ${count} occurrence(s)`);
}

fs.writeFileSync(file, buf);
console.log(`patched ${changed} string(s) in ${file}`);
