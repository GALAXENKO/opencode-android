const fs = require("fs");
let s = fs.readFileSync("C:/Users/WALKIR/AppData/Local/Temp/oc-config-schema.json", "utf8");
if (s.charCodeAt(0) === 0xfeff) s = s.slice(1);
const j = JSON.parse(s);
console.log("root props:", JSON.stringify(Object.keys(j.properties)));
console.log("additionalProperties:", JSON.stringify(j.additionalProperties));
const defs = j.$defs || j.definitions || {};
console.log("defs:", JSON.stringify(Object.keys(defs)));
const cfg = defs.Config || {};
if (cfg.properties) {
  for (const [k, v] of Object.entries(cfg.properties)) {
    console.log(" -", k, JSON.stringify(v).slice(0, 160));
  }
}
