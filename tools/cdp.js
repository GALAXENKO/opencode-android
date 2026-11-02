// Minimal CDP driver: node cdp.js <wsUrl> <expression-file-or-inline>
const fs = require("fs");

const wsUrl = process.argv[2];
const expr = process.argv[3].startsWith("@")
  ? fs.readFileSync(process.argv[3].slice(1), "utf8")
  : process.argv[3];

const ws = new WebSocket(wsUrl);
let id = 0;
const pending = new Map();

function send(method, params = {}) {
  const mid = ++id;
  return new Promise((resolve, reject) => {
    pending.set(mid, { resolve, reject });
    ws.send(JSON.stringify({ id: mid, method, params }));
  });
}

ws.addEventListener("message", (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
    else p.resolve(msg.result);
  }
});

ws.addEventListener("open", async () => {
  try {
    await send("Runtime.enable");
    const r = await send("Runtime.evaluate", {
      expression: expr,
      awaitPromise: true,
      returnByValue: true,
      userGesture: true,
    });
    if (r.exceptionDetails) {
      console.log("EXCEPTION:", JSON.stringify(r.exceptionDetails, null, 2));
    } else {
      const v = r.result.value;
      console.log(typeof v === "string" ? v : JSON.stringify(v, null, 2));
    }
  } catch (e) {
    console.log("ERROR:", e.message);
  }
  ws.close();
  process.exit(0);
});

ws.addEventListener("error", (e) => {
  console.log("WS ERROR", e.message || String(e));
  process.exit(1);
});
