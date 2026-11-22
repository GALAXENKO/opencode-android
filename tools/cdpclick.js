// Clicks a page element by CSS selector using trusted CDP mouse events.
// usage: node cdpclick.js <wsUrl> <selector>
const wsUrl = process.argv[2];
const selector = process.argv[3];

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
    const box = await send("Runtime.evaluate", {
      expression: `(() => { const e = document.querySelector(${JSON.stringify(selector)});
        if (!e) return null;
        const r = e.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height, label: e.getAttribute("aria-label") }; })()`,
      returnByValue: true,
    });
    const b = box.result.value;
    if (!b) {
      console.log("element not found:", selector);
      process.exit(1);
    }
    console.log("box", JSON.stringify(b));
    for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
      await send("Input.dispatchMouseEvent", {
        type,
        x: b.x,
        y: b.y,
        button: "left",
        buttons: type === "mousePressed" ? 1 : 0,
        clickCount: 1,
      });
    }
    console.log("clicked", selector, "at", Math.round(b.x), Math.round(b.y));
  } catch (e) {
    console.log("ERROR:", e.message);
  }
  ws.close();
  process.exit(0);
});
