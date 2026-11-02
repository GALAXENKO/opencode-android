// Measure how much writable NUL-padded room exists around standalone strings.
const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
const targets = process.argv.slice(3);

function find(buf, s) {
  const needle = Buffer.from(s, "latin1");
  const hits = [];
  let i = 0;
  for (;;) {
    const j = buf.indexOf(needle, i);
    if (j < 0) break;
    hits.push(j);
    i = j + 1;
  }
  return hits;
}

for (const t of targets) {
  const hits = find(buf, t);
  console.log(`=== ${t} : ${hits.length} hit(s)`);
  for (const off of hits) {
    // count NUL run before and after the string
    let before = 0;
    for (let k = off - 1; k >= 0 && buf[k] === 0; k--) before++;
    let end = off + t.length;
    let after = 0;
    for (let k = end; k < buf.length && buf[k] === 0; k++) after++;
    // previous non-nul string ending before
    let prevEnd = off - before; // first byte of NUL run
    // check if the byte right before the NUL run is printable (part of another string)
    const prevByte = prevEnd > 0 ? buf[prevEnd - 1] : 0;
    console.log(
      `  off=${off} (0x${off.toString(16)}) beforeNul=${before} afterNul=${after} prevByte=0x${prevByte.toString(16)} totalRoom=${before + t.length + after}`
    );
    const ctxStart = Math.max(0, off - 24);
    const ctxEnd = Math.min(buf.length, off + t.length + 24);
    console.log("   ctx: " + JSON.stringify(buf.toString("latin1", ctxStart, ctxEnd)));
  }
}
