// Taps at CSS coords and prints console errors / exceptions raised by the page.
// usage: node cdptap.js <wsUrl> <x> <y>
const wsUrl = process.argv[2];
const x = Number(process.argv[3]);
const y = Number(process.argv[4]);

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
    return;
  }
  if (msg.method === "Runtime.consoleAPICalled") {
    const text = msg.params.args.map((a) => a.value ?? a.description ?? a.type).join(" ");
    console.log(`[console.${msg.params.type}] ${text}`);
  }
  if (msg.method === "Runtime.exceptionThrown") {
    const d = msg.params.exceptionDetails;
    console.log(`[exception] ${d.text} ${d.exception?.description ?? ""}`.slice(0, 800));
  }
  if (msg.method === "Log.entryAdded") {
    const e = msg.params.entry;
    console.log(`[log.${e.level}] ${e.text}`.slice(0, 800));
  }
});

ws.addEventListener("open", async () => {
  try {
    await send("Runtime.enable");
    await send("Log.enable");
    for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
      await send("Input.dispatchMouseEvent", {
        type,
        x,
        y,
        button: "left",
        buttons: type === "mousePressed" ? 1 : 0,
        clickCount: 1,
      });
    }
    await new Promise((r) => setTimeout(r, 2500));
    const state = await send("Runtime.evaluate", {
      expression:
        "JSON.stringify({expanded:(document.querySelector('button[data-action=composer-attach]')||{}).ariaExpanded, menus:document.querySelectorAll('[role=menu]').length, popovers:document.querySelectorAll('[popover]').length, dialogs:document.querySelectorAll('dialog').length, bodyKids:document.body.children.length})",
      returnByValue: true,
    });
    console.log("state:", state.result.value);
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
