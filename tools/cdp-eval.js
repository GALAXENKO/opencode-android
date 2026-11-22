// Small CDP client for the app's WebView: evaluates expressions and can type into the composer.
// usage: node cdp-eval.js <wsUrl> <expression>   (expression may use `await`)
const wsUrl = process.argv[2];
const expression = process.argv[3];

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
    const r = await send("Runtime.evaluate", {
      expression: `(async () => { ${expression} })()`,
      awaitPromise: true,
      returnByValue: true,
    });
    if (r.exceptionDetails) console.log("EXC", JSON.stringify(r.exceptionDetails).slice(0, 800));
    else console.log(typeof r.result.value === "string" ? r.result.value : JSON.stringify(r.result.value));
  } catch (e) {
    console.log("ERR", e.message);
  }
  ws.close();
});
