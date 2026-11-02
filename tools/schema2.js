const fs = require("fs");
let t = fs.readFileSync("C:/Users/WALKIR/AppData/Local/Temp/oc-config-schema.json", "utf8");
t = t.replace(/^\uFEFF/, "");
const j = JSON.parse(t);
const defs = j.defs || j.$defs || {};
const show = (name) => {
  console.log("=== " + name + " ===");
  console.log(JSON.stringify(defs[name], null, 1));
};
show("Config.ProviderEncoded");
