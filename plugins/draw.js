// Draw — a 🎨 button opens a drawing pad. Pen, eraser, colors, sizes, undo, clear, and 📤 sends the picture
// into the text field (or copies it when the app can't take images). Built on keys.canvas — read it as an example.
plugin({
  name: "Draw",
  description: "Sketch on a canvas and send it as an image. Stylus pressure supported.",
  settings: {
    background: {type: "select", label: "Paper", default: "white", options: ["white", "black", "transparent"]},
    size: {type: "number", label: "Default pen size (dp)", default: 4},
  },
  toolbar: [{
    id: "open",
    icon: "🎨",
    run() {
      const COLORS = ["#000000", "#e53935", "#fb8c00", "#fdd835", "#43a047", "#1e88e5", "#8e24aa", "#ffffff"];
      const DOTS = ["⚫", "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚪"];
      const SIZES = [2, 5, 10, 20];
      const paper = keys.settings.background || "white";
      let color = paper === "black" ? "#ffffff" : "#000000";
      let size = +keys.settings.size || 4;
      let eraser = false, last = null, pid = null;

      const c = keys.canvas({
        title: "Draw",
        background: paper === "transparent" ? "#808080" : paper,
        onTouch(e) {
          if (e.type === "down" && pid === null) {
            pid = e.id; last = e;
            c.save();                                   // snapshot for undo
            c.dot(e.x, e.y, {color: color, width: w(e), erase: eraser});
          } else if (e.id === pid && e.type === "move") {
            c.line(last.x, last.y, e.x, e.y, {color: color, width: w(e), erase: eraser});
            last = e;
          } else if (e.id === pid && (e.type === "up" || e.type === "cancel")) {
            pid = null; last = null;
          }
        },
      });
      const w = (e) => (eraser ? size * 3 : size) * (e.stylus ? 0.4 + e.pressure * 1.2 : 1);

      const ui = () => c.buttons([
        {label: "✏️", active: !eraser, onTap() { eraser = false; ui(); }},
        {label: "🧽", active: eraser, onTap() { eraser = true; ui(); }},
        ...COLORS.map((col, i) => ({label: DOTS[i], active: !eraser && color === col, onTap() { color = col; eraser = false; ui(); }})),
        ...SIZES.map((s) => ({label: s < 4 ? "S" : s < 8 ? "M" : s < 15 ? "L" : "XL", active: size === s, onTap() { size = s; ui(); }})),
        {label: "↶", onTap() { c.restore(); }},
        {label: "🗑", onTap() { c.save(); c.clear(); }},
        {label: "📤", onTap() { c.insertImage({transparent: paper === "transparent"}); }},
      ]);
      ui();
    },
  }],
});
