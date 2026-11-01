// Camel Case — select text, tap 🐫, it becomes camelCase.
plugin({
  name: "Camel Case",
  description: "Select some text, tap 🐫 → camelCase.",
  toolbar: [{
    id: "run",
    icon: "🐫",
    run() {
      const s = keys.selected();
      if (!s) return keys.toast("Select some text first");
      keys.commit(toCamel(s));          // committing while text is selected replaces it
    },
  }],
});

function toCamel(s) {
  return s
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")   // helloWorld -> hello World
    .trim()
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((w, i) => {
      w = w.toLowerCase();
      return i === 0 ? w : w[0].toUpperCase() + w.slice(1);
    })
    .join("");
}
