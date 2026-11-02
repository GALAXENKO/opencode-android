const fs = require('fs');
const path = process.argv[2];
const fd = fs.openSync(path, 'r');
const size = fs.fstatSync(fd).size;
function readAt(buf, len, pos) { fs.readSync(fd, buf, 0, len, pos); }
const head = Buffer.alloc(64); readAt(head, 64, 0);
const le = head[5] === 1;
const u16 = (b, o) => le ? b.readUInt16LE(o) : b.readUInt16BE(o);
const u32 = (b, o) => le ? b.readUInt32LE(o) : b.readUInt32BE(o);
const u64 = (b, o) => Number(le ? b.readBigUInt64LE(o) : b.readBigUInt64BE(o));
const phoff = u64(head, 32), phentsize = u16(head, 54), phnum = u16(head, 56);
const ph = Buffer.alloc(phentsize * phnum); readAt(ph, ph.length, phoff);
let dyn = null;
const loads = [];
for (let i = 0; i < phnum; i++) {
  const o = i * phentsize, type = u32(ph, o);
  const off = u64(ph, o + 8), vaddr = u64(ph, o + 16), filesz = u64(ph, o + 32), align = u64(ph, o + 48);
  if (type === 2) dyn = { off, vaddr, filesz };
  if (type === 1) loads.push({ off, vaddr, filesz, align });
}
function v2o(v) {
  for (const l of loads) if (v >= l.vaddr && v < l.vaddr + l.filesz) return v - l.vaddr + l.off;
  return null;
}
const dynBuf = Buffer.alloc(dyn.filesz); readAt(dynBuf, dyn.filesz, dyn.off);
const TAG = { 1: 'NEEDED', 5: 'STRTAB', 10: 'STRSZ', 14: 'SONAME', 21: 'DEBUG', 29: 'RUNPATH', 15: 'RPATH', 30: 'FLAGS_1' };
const needed = [];
let strtabOff = null, strsz = 0;
const entries = [];
for (let o = 0; o + 16 <= dynBuf.length; o += 16) {
  const tag = Number(le ? dynBuf.readBigInt64LE(o) : dynBuf.readBigInt64BE(o));
  const val = u64(dynBuf, o + 8);
  if (tag === 0) break;
  entries.push([tag, val]);
}
for (const [tag, val] of entries) {
  if (tag === 5) strtabOff = v2o(val);
  if (tag === 10) strsz = val;
}
const strtab = Buffer.alloc(strsz); readAt(strtab, strsz, strtabOff);
const cstr = (buf, off) => { let e = off; while (e < buf.length && buf[e] !== 0) e++; return buf.toString('latin1', off, e); };
for (const [tag, val] of entries) {
  if (TAG[tag]) console.log(TAG[tag], '=', tag === 5 || tag === 10 ? val : cstr(strtab, val));
}
fs.closeSync(fd);
