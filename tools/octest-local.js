// Creates a session, switches it to a model, sends a prompt and prints the reply.
// usage: node octest-local.js <port> <password> <providerID> <modelID> [text]
const crypto = require("crypto");
const port = process.argv[2];
const pw = process.argv[3];
const providerID = process.argv[4] || "local";
const modelID = process.argv[5] || "local";
const text = process.argv[6] || "Reply with the single word: pong";

const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

async function call(method, path, body) {
  const r = await fetch(`http://127.0.0.1:${port}${path}`, {
    method,
    headers: { cookie, "content-type": "application/json", accept: "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(180000),
  });
  const t = await r.text();
  let j = null;
  try { j = JSON.parse(t); } catch {}
  return { status: r.status, json: j, text: t };
}

(async () => {
  const created = await call("POST", "/api/session", { title: "local model check" });
  console.log("create", created.status, JSON.stringify(created.json).slice(0, 300));
  const id = created.json && created.json.data && created.json.data.id;
  if (!id) return;

  const switched = await call("POST", `/api/session/${id}/model`, { model: { providerID, id: modelID } });
  console.log("model", switched.status, JSON.stringify(switched.json).slice(0, 300));

  const sent = await call("POST", `/api/session/${id}/prompt`, { text });
  console.log("prompt", sent.status, JSON.stringify(sent.json).slice(0, 300));

  const deadline = Date.now() + 120000;
  let last = "";
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 2000));
    const list = await call("GET", `/api/session/${id}/message`);
    if (list.status !== 200 || !list.json) continue;
    const msgs = list.json.data || list.json;
    const flat = JSON.stringify(msgs);
    if (flat === last) continue;
    last = flat;
    const assistant = msgs.filter((m) => (m.info && m.info.role === "assistant") || m.role === "assistant");
    const parts = [];
    for (const m of assistant) {
      for (const p of m.parts || []) if (p.type === "text") parts.push(p.text);
      if (m.info && m.info.error) parts.push("ERROR " + JSON.stringify(m.info.error));
    }
    if (parts.length) {
      console.log("assistant:", parts.join(" | ").slice(0, 600));
      break;
    }
  }
  console.log("session", id);
})().catch((e) => console.log("ERR", e.message));
