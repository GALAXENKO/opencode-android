// Clicks the first element whose text matches, using trusted CDP mouse events.
// usage: node cdp-textclick.js <wsUrl> <text>
const wsUrl = process.argv[2];
const text = process.argv[3];

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
    const found = await send("Runtime.evaluate", {
      expression: `(() => {
        const t = ${JSON.stringify(text)};
        const all = [...document.querySelectorAll('button,a,[role=button],[role=link],[role=menuitem],div,span')];
        const el = all.find((e) => (e.innerText || '').trim() === t && e.getBoundingClientRect().width > 0);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2, tag: el.tagName };
      })()`,
      returnByValue: true,
    });
    const box = found.result.value;
    if (!box) {
      console.log("not found:", text);
      ws.close();
      return;
    }
    for (const type of ["mousePressed", "mouseReleased"]) {
      await send("Input.dispatchMouseEvent", {
        type,
        x: box.x,
        y: box.y,
        button: "left",
        clickCount: 1,
      });
    }
    console.log("clicked", box.tag, box.x.toFixed(0), box.y.toFixed(0));
  } catch (e) {
    console.log("ERR", e.message);
  }
  ws.close();
});
