const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
function findAll(n) {
  const b = Buffer.from(n, "latin1");
  const hits = [];
  let i = buf.indexOf(b);
  while (i >= 0) { hits.push(i); i = buf.indexOf(b, i + 1); }
  return hits;
}
const needles = [
  "/etc/passwd", "/etc/group", "/etc/hosts", "/etc/resolv.conf", "/etc/nsswitch.conf",
  "/etc/localtime", "/etc/services", "/etc/protocols", "/dev/urandom", "/dev/null",
  "/proc/self/exe", "/tmp", "-----BEGIN CERTIFICATE-----", "ca-certificates",
  "/bin/sh", "/bin/bash", "bash", "SHELL", "os.userInfo", "userInfo",
];
for (const n of needles) {
  const hits = findAll(n);
  console.log(n.padEnd(28), hits.length, hits.slice(0, 6).join(","));
}
console.log("--- standalone (NUL on both sides) for key paths ---");
for (const n of ["/etc/passwd", "/etc/group", "/etc/hosts", "/etc/resolv.conf"]) {
  for (const h of findAll(n)) {
    const before = buf[h - 1], after = buf[h + n.length];
    console.log(n, "@" + h, "before=" + before, "after=" + after);
  }
}
