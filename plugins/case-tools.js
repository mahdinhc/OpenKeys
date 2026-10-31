// Case Tools — one "Aa" button that opens a panel of case conversions for the selected text.
plugin({
  name: "Case Tools",
  description: "Select text, tap Aa, pick: UPPER, lower, Title, snake_case, kebab-case, camelCase, PascalCase.",
  toolbar: [{
    id: "menu",
    icon: "Aa",
    run() {
      if (!keys.selected()) return keys.toast("Select some text first");
      keys.panel({
        title: "Change case",
        columns: 2,
        items: [
          ["UPPERCASE", (s) => s.toUpperCase()],
          ["lowercase", (s) => s.toLowerCase()],
          ["Title Case", (s) => words(s).map(cap).join(" ")],
          ["Sentence case", (s) => cap(s.toLowerCase())],
          ["snake_case", (s) => words(s).map(lc).join("_")],
          ["kebab-case", (s) => words(s).map(lc).join("-")],
          ["camelCase", (s) => words(s).map((w, i) => (i ? cap(w) : lc(w))).join("")],
          ["PascalCase", (s) => words(s).map(cap).join("")],
        ].map(([label, fn]) => ({label, onTap: () => apply(fn)})),
      });
    },
  }],
});

const lc = (w) => w.toLowerCase();
const cap = (w) => (w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : w);
const words = (s) => s.replace(/([a-z0-9])([A-Z])/g, "$1 $2").split(/[^A-Za-z0-9]+/).filter(Boolean);

function apply(fn) {
  const s = keys.selected();          // fresh snapshot taken when the panel item was tapped
  if (!s) return keys.toast("Selection was lost");
  keys.commit(fn(s));
}
