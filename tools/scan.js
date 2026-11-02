const fs = require("fs");
const file = process.argv[2];
const buf = fs.readFileSync(file);
console.log("size", buf.length);
const needles = [
  "/etc/resolv.conf", "/etc/ssl/certs", "ca-certificates.crt", "/etc/hosts",
  "/etc/nsswitch.conf", "/proc/self/exe", "/etc/ssl/cert.pem", "SSL_CERT_FILE",
  "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "nameserver", "/etc/passwd", "/etc/group",
  "/tmp/", "musl", "BUN_", "opencode_session", "auth_token"
];
for (const n of needles) {
  const b = Buffer.from(n, "latin1");
  const hits = [];
  let i = buf.indexOf(b);
  while (i >= 0 && hits.length < 20) { hits.push(i); i = buf.indexOf(b, i + 1); }
  console.log(n.padEnd(24), hits.length, hits.join(","));
}
