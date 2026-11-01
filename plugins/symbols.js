// Symbols — an Ω button with arrows, currency, math, punctuation and other symbols that are hard to find on a phone keyboard.
plugin({
  name: "Symbols",
  description: "Tap Ω for arrows, currency, math, punctuation and more.",
  toolbar: [{id: "menu", icon: "Ω", run: () => menu()}],
});

const SETS = {
  "Arrows": "← → ↑ ↓ ↔ ↕ ⇐ ⇒ ⇑ ⇓ ⇔ ↩ ↪ ➜ ➔ ↗ ↘ ↖ ↙ ⟵ ⟶ ↻ ↺".split(" "),
  "Currency": "$ € £ ¥ ₹ ৳ ₽ ₩ ₺ ₫ ฿ ₪ ₦ ₱ ¢ ¤ ₿".split(" "),
  "Math": "± × ÷ ≈ ≠ ≤ ≥ ∞ √ ∑ ∏ ∆ π ° ² ³ ½ ¼ ¾ ‰ µ ∂ ∫ ∈ ∩ ∪".split(" "),
  "Punctuation": "… – — · • ‹ › « » “ ” ‘ ’ ¿ ¡ § ¶ † ‡ № ™ © ® ‽".split(" "),
  "Marks": "✓ ✔ ✗ ✘ ★ ☆ ♥ ♡ ♪ ♫ ☀ ☁ ☂ ☺ ☹ ✉ ✂ ✈ ⚠ ⚡ ☎ ✿ ❤".split(" "),
};

function menu() {
  keys.panel({
    title: "Symbols",
    columns: 3,
    items: Object.keys(SETS).map((name) => ({label: name + "  " + SETS[name].slice(0, 3).join(" "), onTap: () => show(name)})),
  });
}

function show(name) {
  // Panels close after a tap, so the next one is opened from the next tick.
  setTimeout(() => keys.panel({
    title: name,
    columns: 6,
    keepOpen: true,
    items: SETS[name].map((c) => ({label: c, text: c})).concat([{label: "‹ Back", onTap: () => menu()}]),
  }), 0);
}
