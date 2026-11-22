// Prints the messages of one session.
// usage: node ocmessages.js <port> <password> <sessionID>
const crypto = require("crypto");
const [port, pw, session] = process.argv.slice(2);
const exp = String(Math.floor(Date.now() / 1000) + 2592000);
const inner = crypto.createHmac("sha256", pw).update("opencode-session-v1").digest();
const sig = crypto.createHmac("sha256", inner).update(exp).digest("base64url");
const cookie = `opencode_session_${port}=${exp}.${sig}`;

(async () => {
  const r = await fetch(`http://127.0.0.1:${port}/api/session/${session}/message`, { headers: { cookie } });
  const j = await r.json();
  for (const m of j.data || []) {
    const body = m.text ?? (m.content || []).map((p) => `${p.type}:${p.text ?? p.tool ?? ""}`).join(" ");
    console.log(`--- ${m.type} ${m.finish ?? ""} ${JSON.stringify(m.error ?? "")}`);
    console.log(String(body).slice(0, 900));
  }
})();
