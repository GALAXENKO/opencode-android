// Types text into the composer using trusted CDP input and clicks send.
// usage: node cdp-send.js <wsUrl> <text>
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

async function click(x, y) {
  for (const type of ["mousePressed", "mouseReleased"]) {
    await send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1 });
  }
}

ws.addEventListener("open", async () => {
  try {
    const box = await send("Runtime.evaluate", {
      expression: `(() => {
        const el = [...document.querySelectorAll('[contenteditable=true]')].find((e) => e.getBoundingClientRect().width > 0);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      })()`,
      returnByValue: true,
    });
    const c = box.result.value;
    if (!c) throw new Error("composer not found");
    await click(c.x, c.y);
    await send("Input.insertText", { text });
    await new Promise((r) => setTimeout(r, 400));
    const btn = await send("Runtime.evaluate", {
      expression: `(() => {
        const el = [...document.querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === 'Отправить' && b.getBoundingClientRect().width > 0);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2, disabled: el.disabled };
      })()`,
      returnByValue: true,
    });
    const b = btn.result.value;
    if (!b) throw new Error("send button not found");
    if (b.disabled) throw new Error("send button disabled");
    await click(b.x, b.y);
    console.log("sent");
  } catch (e) {
    console.log("ERR", e.message);
  }
  ws.close();
});
