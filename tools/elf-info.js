// Minimal ELF reader: prints class, PT_INTERP, DT_NEEDED and RPATH/RUNPATH.
// usage: node tools/elf-info.js <file> [<file>...]
const fs = require('fs')

function info (path) {
  const b = fs.readFileSync(path)
  if (b[0] !== 0x7f || b[1] !== 0x45 || b[2] !== 0x4c || b[3] !== 0x46) return { path, error: 'not an ELF' }
  const is64 = b[4] === 2
  const le = b[5] === 1
  const rv = (o, n) => {
    let v = 0n
    for (let i = n - 1; i >= 0; i--) v = (v << 8n) | BigInt(b[o + (le ? i : n - 1 - i)])
    return v
  }
  const num = (o, n) => Number(rv(o, n))
  const eh = is64
    ? { phoff: num(0x20, 8), shoff: num(0x28, 8), phentsize: num(0x36, 2), phnum: num(0x38, 2), shentsize: num(0x3a, 2), shnum: num(0x3c, 2) }
    : { phoff: num(0x1c, 4), shoff: num(0x20, 4), phentsize: num(0x2a, 2), phnum: num(0x2c, 2), shentsize: num(0x2e, 2), shnum: num(0x30, 2) }
  const out = { path, class: is64 ? 64 : 32, interp: null, needed: [], rpath: [], runpath: [], type: num(0x10, 2) }

  // program headers
  let dyn = null
  for (let i = 0; i < eh.phnum; i++) {
    const off = eh.phoff + i * eh.phentsize
    const type = num(off, 4)
    const foff = is64 ? num(off + 8, 8) : num(off + 4, 4)
    const fsz = is64 ? num(off + 32, 8) : num(off + 16, 4)
    if (type === 3 && fsz) out.interp = b.slice(foff, foff + fsz).toString('latin1').replace(/\0.*$/, '')
    if (type === 2) dyn = { off: foff, size: fsz }
  }
  if (!dyn) {
    for (let i = 0; i < eh.shnum; i++) {
      const off = eh.shoff + i * eh.shentsize
      if (num(off + 4, 4) === 6) {
        const foff = is64 ? num(off + 24, 8) : num(off + 16, 4)
        const fsz = is64 ? num(off + 32, 8) : num(off + 20, 4)
        dyn = { off: foff, size: fsz }
      }
    }
  }
  if (dyn) {
    const entsz = is64 ? 16 : 8
    let strtab = null
    const entries = []
    for (let o = dyn.off; o < dyn.off + dyn.size; o += entsz) {
      const tag = is64 ? num(o, 8) : num(o, 4)
      const val = is64 ? num(o + 8, 8) : num(o + 4, 4)
      if (tag === 0) break
      entries.push([tag, val])
      if (tag === 5) strtab = val
    }
    if (strtab != null) {
      const str = (p) => {
        let e = strtab + p
        while (b[e] !== 0) e++
        return b.slice(strtab + p, e).toString('latin1')
      }
      for (const [tag, val] of entries) {
        if (tag === 1) out.needed.push(str(val))
        if (tag === 15) out.rpath.push(str(val))
        if (tag === 29) out.runpath.push(str(val))
      }
    }
  }
  return out
}

for (const p of process.argv.slice(2)) {
  const r = info(p)
  console.log(JSON.stringify(r))
}
