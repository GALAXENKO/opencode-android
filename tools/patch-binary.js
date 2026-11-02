// Patch absolute /etc paths that cannot exist on Android into relative paths
// resolved against the process working directory (we set cwd = app private HOME).
//
// Only NUL-delimited standalone C strings are patched; occurrences embedded in
// the JS bundle (quoted, not NUL terminated) are left untouched because changing
// their length would corrupt the embedded source blob.
const fs = require("fs");

const [, , src, dst] = process.argv;
if (!src || !dst) {
  console.error("usage: node patch-binary.js <src> <dst>");
  process.exit(2);
}

const PATCHES = [
  { from: "/etc/resolv.conf", to: "resolv.conf" },
  { from: "/etc/hosts", to: "hosts" },
];

const buf = fs.readFileSync(src);

function isNulTerminatedAt(off, len) {
  const before = off > 0 ? buf[off - 1] : 0;
  const after = off + len < buf.length ? buf[off + len] : 0;
  return before === 0 && after === 0;
}

let total = 0;
for (const p of PATCHES) {
  const needle = Buffer.from(p.from, "latin1");
  const repl = Buffer.from(p.to, "latin1");
  if (repl.length > needle.length) {
    console.error(`skip ${p.from}: replacement is longer`);
    continue;
  }
  let i = 0;
  let n = 0;
  for (;;) {
    const off = buf.indexOf(needle, i);
    if (off < 0) break;
    i = off + 1;
    if (!isNulTerminatedAt(off, needle.length)) {
      console.log(`  skip embedded @0x${off.toString(16)} ${p.from}`);
      continue;
    }
    buf.fill(0, off, off + needle.length);
    repl.copy(buf, off);
    n++;
    total++;
    console.log(`  patched @0x${off.toString(16)} ${p.from} -> ${p.to}`);
  }
  console.log(`${p.from}: ${n} standalone occurrence(s)`);
}

fs.writeFileSync(dst, buf);
console.log(`wrote ${dst} (${buf.length} bytes, ${total} patches)`);
