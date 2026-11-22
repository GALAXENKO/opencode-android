// OpenCode plugin: keeps requests to the on-device model small.
//
// A local llama.cpp model on a phone pays for every prompt token twice: once when the
// prompt is prefilled and again in RAM for the KV cache. The default "build" agent ships
// ~4k tokens of tool schemas plus a long system prompt, which is most of a 16k window
// before the user has typed anything. This plugin strips both for the `local` provider
// only, leaving cloud providers untouched.
//
// Installed by the Android app into ~/.config/opencode/plugins/local-lite/ and listed in
// opencode.jsonc under "plugins" while the light mode is on.
const PROVIDER = "local";

// Short replacement for the agent's system prompt. Kept in English: every model in the
// catalogue follows it, and English tokenises more compactly than Russian.
const SYSTEM = [
  "You are a helpful assistant running offline on the user's phone.",
  "Answer in the user's language, concisely, in plain text.",
  "You have no tools: do not claim to run commands, read files or open links.",
].join(" ");

/** Provider of the model the request is being built for, however the event spells it. */
function providerOf(event) {
  return (
    event?.model?.providerID ??
    event?.model?.provider?.id ??
    event?.providerID ??
    event?.provider?.id ??
    ""
  );
}

export default {
  id: "opencode-local-lite",
  async setup(ctx) {
    const light = (event) => {
      if (providerOf(event) !== PROVIDER) return;
      if (event.tools) for (const name of Object.keys(event.tools)) delete event.tools[name];
      if (Array.isArray(event.system) && event.system.length) {
        const first = event.system[0];
        event.system.splice(0, event.system.length, { ...first, text: SYSTEM });
      }
    };
    await ctx.session.hook("context", light);
    await ctx.session.hook("compaction", light);
    await ctx.session.hook("generate", light);
  },
};
