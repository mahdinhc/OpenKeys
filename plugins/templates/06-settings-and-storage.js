// Settings & storage — settings give you a form in Plugins; storage keeps data between sessions.
// Setting types: text, textarea, number, bool, select (with options).
plugin({
  name: "Counter",
  description: "Counts how many times you tap the button.",
  settings: {
    step: {type: "number", label: "Count by", default: 1},
    verbose: {type: "bool", label: "Show a message", default: true},
    style: {type: "select", label: "Style", default: "plain", options: ["plain", "fancy"]},
  },
  toolbar: [{
    icon: "➕",
    run() {
      const n = Number(keys.storage.get("n", 0)) + Number(keys.settings.step || 1);
      keys.storage.set("n", n);
      if (keys.settings.verbose) keys.toast(keys.settings.style === "fancy" ? "✨ " + n + " ✨" : String(n));
    },
  }],
});
