// Removes a provider block from the on-device opencode config (test leftovers).
const { spawnSync } = require("child_process");

const ADB = "F:\\oc\\buildenv\\sdk\\platform-tools\\adb.exe";
const PKG = "ai.opencode.mobile";
const REMOTE = "files/home/.config/opencode/opencode.jsonc";
const key = process.argv[2] || "smoketest";

function adb(args, input) {
  const r = spawnSync(ADB, args, { input, maxBuffer: 64 * 1024 * 1024 });
  if (r.error) throw r.error;
  if (r.status !== 0) throw new Error("adb failed: " + r.stderr.toString());
  return r.stdout.toString("utf8");
}

const text = adb(["exec-out", "run-as", PKG, "cat", REMOTE]);
if (!text.includes(`"${key}"`)) {
  console.log("nothing to remove: no " + key);
  process.exit(0);
}

const start = text.indexOf(`"${key}"`);
let i = text.indexOf("{", start);
let depth = 0;
let end = -1;
for (; i < text.length; i++) {
  const c = text[i];
  if (c === '"') {
    // skip string
    for (i++; i < text.length; i++) {
      if (text[i] === "\\") i++;
      else if (text[i] === '"') break;
    }
    continue;
  }
  if (c === "{") depth++;
  else if (c === "}") {
    depth--;
    if (depth === 0) {
      end = i + 1;
      break;
    }
  }
}
if (end < 0) throw new Error("unbalanced braces");

// cut from the start of the line holding the key up to and including the trailing comma
let cutStart = text.lastIndexOf("\n", start) + 1;
let cutEnd = end;
while (cutEnd < text.length && /[ \t\r\n]/.test(text[cutEnd])) cutEnd++;
if (text[cutEnd] === ",") cutEnd++;

const out = text.slice(0, cutStart) + text.slice(cutEnd);
const written = adb(["shell", `run-as ${PKG} sh -c 'cat > ${REMOTE}'`], out);
const back = adb(["exec-out", "run-as", PKG, "cat", REMOTE]);
console.log("before:", text.length, "after:", back.length, "key present:", back.includes(`"${key}"`), written.trim());
