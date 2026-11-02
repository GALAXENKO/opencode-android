// Look for DNS/network related env var strings and their context.
const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
const needles = [
  "CARES_HOSTS",
  "RES_OPTIONS",
  "LOCALDOMAIN",
  "resolv.conf",
  "BUN_CONFIG_DNS",
  "ndots",
  "nameserver",
  "ares_",
];
const s = buf.toString("latin1");
for (const n of needles) {
  const hits = [];
  let i = 0;
  for (;;) {
    const j = s.indexOf(n, i);
    if (j < 0) break;
    hits.push(j);
    i = j + 1;
    if (hits.length > 40) break;
  }
  console.log(`=== ${n}: ${hits.length}`);
  for (const off of hits.slice(0, 12)) {
    const a = Math.max(0, off - 70);
    const b = Math.min(s.length, off + 70);
    const ctx = s
      .slice(a, b)
      .replace(/[\x00-\x08\x0b\x0c\x0e-\x1f]/g, (c) => "\\x" + c.charCodeAt(0).toString(16).padStart(2, "0"));
    console.log(`  @${off} ${ctx}`);
  }
}
