// Real (trusted) input through CDP: node cdp-input.js <wsUrl> tap <x> <y>
//                                          node cdp-input.js <wsUrl> type <text>
// Uses Input.dispatchMouseEvent so the WebView sees a genuine user gesture
// (a JS .click() is not enough to open the file chooser).
const wsUrl = process.argv[2];
const mode = process.argv[3];

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
    if (mode === "tap") {
      const x = Number(process.argv[4]);
      const y = Number(process.argv[5]);
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
      console.log("tapped", x, y);
    } else if (mode === "type") {
      await send("Input.insertText", { text: process.argv[4] });
      console.log("typed", process.argv[4].length, "chars");
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
