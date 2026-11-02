const fs = require("fs");
let t = fs.readFileSync("C:/Users/WALKIR/AppData/Local/Temp/oc-config-schema.json", "utf8");
t = t.replace(/^\uFEFF/, "");
const j = JSON.parse(t);
console.log("TOP:", Object.keys(j.properties).join(", "));
const defs = j.defs || j.$defs || {};
console.log("DEFS:", Object.keys(defs).join(", "));
if (j.properties.shell) console.log("SHELL:", JSON.stringify(j.properties.shell));
if (j.properties.providers) console.log("PROVIDERS:", JSON.stringify(j.properties.providers, null, 2).slice(0, 2000));
