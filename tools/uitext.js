// Prints text/content-desc of nodes from a uiautomator dump, as real UTF-8.
// usage: node uitext.js <dump.xml> [filter]
const fs = require("fs");
const file = process.argv[2];
const filter = process.argv[3];
const s = fs.readFileSync(file, "utf8");
const nodes = s.match(/<node\s[^>]*>/g) || [];
const out = [];
for (const n of nodes) {
  const attr = (name) => {
    const m = new RegExp(name + '="([^"]*)"').exec(n);
    return m ? m[1] : "";
  };
  const text = attr("text") || attr("content-desc");
  const rid = attr("resource-id");
  const cls = attr("class");
  const bd = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(n);
  if (!text && !rid) continue;
  const line = `${cls} rid=${rid} [${bd ? bd[1] + "," + bd[2] + " " + bd[3] + "," + bd[4] : "?"}] ${text}`;
  if (!filter || line.includes(filter)) out.push(line);
}
console.log(out.join("\n"));
