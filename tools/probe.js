// node probe.js <expression-file>
const fs = require("fs");
const expr = fs.readFileSync(process.argv[2], "utf8");

async function main() {
  const list = await (await fetch("http://127.0.0.1:9222/json")).json();
  for (const t of list) {
    if (t.type !== "page") continue;
    console.log("=== page", t.id, "url=", JSON.stringify(t.url));
    const out = await evaluate(t.webSocketDebuggerUrl, expr);
    console.log(out);
  }
}

function evaluate(wsUrl, expression) {
  return new Promise((resolve) => {
    const ws = new WebSocket(wsUrl);
    let id = 0;
    const timer = setTimeout(() => { try { ws.close(); } catch {} resolve("TIMEOUT"); }, 15000);
    ws.addEventListener("open", () => {
      ws.send(JSON.stringify({ id: ++id, method: "Runtime.evaluate", params: { expression, awaitPromise: true, returnByValue: true } }));
    });
    ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(ev.data);
      if (!msg.id) return;
      clearTimeout(timer);
      if (msg.error) resolve("CDP ERROR " + JSON.stringify(msg.error));
      else if (msg.result && msg.result.exceptionDetails) resolve("EXCEPTION " + JSON.stringify(msg.result.exceptionDetails.text || msg.result.exceptionDetails));
      else resolve(typeof msg.result.result.value === "string" ? msg.result.result.value : JSON.stringify(msg.result.result.value));
      ws.close();
    });
    ws.addEventListener("error", (e) => { clearTimeout(timer); resolve("WS ERROR " + (e.message || "")); });
  });
}

main().catch((e) => { console.log("FATAL", e.message); process.exit(1); });
