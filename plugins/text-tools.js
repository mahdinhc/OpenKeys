// Text Tools — select text, tap 🛠, pick: sort lines, remove duplicate lines, reverse, clean spaces, lists, URL encode/decode...
plugin({
  name: "Text Tools",
  description: "Select text, tap 🛠: sort / dedupe / reverse lines, clean spaces, bullet & numbered lists, URL encode/decode.",
  toolbar: [{
    id: "menu",
    icon: "🛠",
    run() {
      if (!keys.selected()) return keys.toast("Select some text first");
      keys.panel({
        title: "Text tools",
        columns: 2,
        items: [
          ["Sort lines A→Z", (s) => lines(s).sort(cmp).join("\n")],
          ["Sort lines Z→A", (s) => lines(s).sort(cmp).reverse().join("\n")],
          ["Remove duplicate lines", (s) => lines(s).filter((l, i, a) => a.indexOf(l) === i).join("\n")],
          ["Reverse line order", (s) => lines(s).reverse().join("\n")],
          ["Reverse text", (s) => Array.from(s).reverse().join("")],
          ["Clean spaces", (s) => s.replace(/[ \t]+/g, " ").replace(/ *\n */g, "\n").replace(/\n{3,}/g, "\n\n").trim()],
          ["Remove line breaks", (s) => s.replace(/\s*\n\s*/g, " ").trim()],
          ["• Bullet list", (s) => lines(s).filter(Boolean).map((l) => "• " + l.replace(/^(\d+[.)]|[-*•])\s+/, "")).join("\n")],
          ["1. Numbered list", (s) => lines(s).filter(Boolean).map((l, i) => (i + 1) + ". " + l.replace(/^(\d+[.)]|[-*•])\s+/, "")).join("\n")],
          ["URL encode", (s) => encodeURIComponent(s)],
          ["URL decode", (s) => { try { return decodeURIComponent(s); } catch (e) { return s; } }],
          ["Remove accents", (s) => s.normalize("NFD").replace(/[\u0300-\u036f]/g, "")],
        ].map(([label, fn]) => ({label, onTap: () => apply(fn)})),
      });
    },
  }],
});

const lines = (s) => s.replace(/\r\n/g, "\n").split("\n");
const cmp = (a, b) => a.localeCompare(b, undefined, {numeric: true, sensitivity: "base"});

function apply(fn) {
  const s = keys.selected();          // fresh snapshot taken when the panel item was tapped
  if (!s) return keys.toast("Selection was lost");
  keys.commit(fn(s));
}
