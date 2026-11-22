// Measures prompt-processing and generation speed of a running llama-server.
// usage: node bench-client.js <port> [promptRepeats] [maxTokens]
const port = process.argv[2] || "40010";
const repeats = Number(process.argv[3] || 200);
const maxTokens = Number(process.argv[4] || 32);

async function post(body, timeout) {
  const t0 = Date.now();
  const r = await fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeout),
  });
  const j = await r.json();
  return { status: r.status, wall: (Date.now() - t0) / 1000, usage: j.usage };
}

(async () => {
  // A unique prompt: no cache hits, so this is real prefill speed.
  const filler = `run-${Date.now()} ` + "The quick brown fox jumps over the lazy dog. ".repeat(repeats);
  const prefill = await post({
    model: "local",
    messages: [{ role: "user", content: filler + " Reply with one word: ok" }],
    max_tokens: 8,
  }, 900000);
  const promptTokens = prefill.usage?.prompt_tokens || 0;

  const gen = await post({
    model: "local",
    messages: [{ role: "user", content: "Write a haiku about a phone." }],
    max_tokens: maxTokens,
  }, 900000);
  const genTokens = gen.usage?.completion_tokens || 0;

  console.log(JSON.stringify({
    prefill: `${promptTokens} tok / ${prefill.wall.toFixed(1)}s = ${(promptTokens / prefill.wall).toFixed(1)} tok/s`,
    generate: `${genTokens} tok / ${gen.wall.toFixed(1)}s = ${(genTokens / gen.wall).toFixed(1)} tok/s`,
  }));
})().catch((e) => console.log("ERR", e.message));
