const fs = require("fs");
const buf = fs.readFileSync(process.argv[2]);
// find all NUL-terminated strings starting with /etc/ or /proc/ or /dev/ or /usr/
const out = new Set();
let i = 0;
const isPathChar = (b) =>
  (b >= 33 && b <= 126);
while (i < buf.length) {
  if (buf[i] === 0x2f /* / */) {
    // walk back to previous NUL
    let s = i;
    while (s > 0 && isPathChar(buf[s - 1])) s--;
    if (s === i) {
      let e = i;
      while (e < buf.length && isPathChar(buf[e]) && e - i < 200) e++;
      if (e < buf.length && buf[e] === 0) {
        const str = buf.toString("latin1", i, e);
        if (/^\/(etc|proc|dev|usr|bin|sbin|lib|var|opt)\//.test(str) && str.length < 80) out.add(str);
      }
    }
    i++;
  } else i++;
}
const arr = [...out].sort();
console.log(arr.length);
for (const s of arr) console.log(s);
