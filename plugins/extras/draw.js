// Draw — a 🎨 button opens a drawing pad: pen, marker, eraser, bucket fill, line/rect/circle, 12 colors, sizes,
// opacity, undo, clear, and 📤 sends the picture into the text field. Built on keys.canvas — read it as an example.
plugin({
  name: "Draw",
  description: "Sketch on a canvas and send it as an image. Stylus pressure supported.",
  settings: {
    background: {type: "select", label: "Paper", default: "white", options: ["white", "black", "transparent"]},
    size: {type: "number", label: "Default pen size (dp)", default: 4},
    height: {type: "number", label: "Canvas height (% of keyboard)", default: 120},
  },
  toolbar: [{
    id: "open",
    icon: "🎨",
    run() {
      const COLORS = ["#000000", "#ffffff", "#e53935", "#fb8c00", "#fdd835", "#7cb342", "#43a047", "#00acc1", "#1e88e5", "#5e35b1", "#d81b60", "#795548"];
      const DOTS = ["⚫", "⚪", "🔴", "🟠", "🟡", "🟢", "🟩", "🩵", "🔵", "🟣", "🩷", "🟤"];
      const SIZES = [["S", 2], ["M", 5], ["L", 10], ["XL", 20]];
      const TOOLS = [["✏️", "pen"], ["🖍", "marker"], ["🧽", "eraser"], ["🪣", "fill"], ["╱", "line"], ["▭", "rect"], ["◯", "circle"]];
      const paper = keys.settings.background || "white";
      let color = paper === "black" ? "#ffffff" : "#000000";
      let size = +keys.settings.size || 4;
      let tool = "pen", opacity = 1, last = null, start = null, pid = null;

      const c = keys.canvas({
        title: "Draw",
        height: +keys.settings.height || 120,
        background: paper === "transparent" ? "#808080" : paper,
        onTouch(e) {
          if (e.type === "down" && pid === null) {
            pid = e.id; last = start = e;
            if (tool === "fill") { c.save(); c.flood(e.x, e.y, withAlpha(color, opacity), 40); pid = null; return; }
            c.save();                                   // snapshot for undo (also the base for shape previews)
            if (tool === "pen" || tool === "marker" || tool === "eraser") c.dot(e.x, e.y, style(e));
          } else if (e.id === pid && e.type === "move") {
            if (tool === "pen" || tool === "marker" || tool === "eraser") { c.line(last.x, last.y, e.x, e.y, style(e)); last = e; }
            else {                                      // live shape preview: roll back to the snapshot, redraw
              c.restore(); c.save();
              shape(start, e);
            }
          } else if (e.id === pid && (e.type === "up" || e.type === "cancel")) {
            pid = null; last = start = null;
          }
        },
      });

      const withAlpha = (hex, a) => hex + (a < 1 ? Math.round(a * 255).toString(16).padStart(2, "0") : "");
      const style = (e) => {
        const w = (tool === "eraser" ? size * 3 : tool === "marker" ? size * 2.5 : size) * (e.stylus ? 0.4 + e.pressure * 1.2 : 1);
        if (tool === "eraser") return {width: w, erase: true};
        // marker = translucent + multiply so overlapping strokes darken like a highlighter
        return tool === "marker" ? {color: color, width: w, alpha: Math.min(opacity, 0.45), cap: "square"} : {color: color, width: w, alpha: opacity};
      };
      const shape = (a, b) => {
        const o = {color: color, width: size, alpha: opacity};
        if (tool === "line") c.line(a.x, a.y, b.x, b.y, o);
        else if (tool === "rect") c.rect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y), o);
        else if (tool === "circle") c.circle(a.x, a.y, Math.hypot(b.x - a.x, b.y - a.y), o);
      };

      const ui = () => c.buttons([
        ...TOOLS.map(([label, id]) => ({label: label, active: tool === id, onTap() { tool = id; ui(); }})),
        ...COLORS.map((col, i) => ({label: DOTS[i], active: tool !== "eraser" && color === col, onTap() { color = col; if (tool === "eraser") tool = "pen"; ui(); }})),
        ...SIZES.map(([label, s]) => ({label: label, active: size === s, onTap() { size = s; ui(); }})),
        {label: "α " + Math.round(opacity * 100) + "%", onTap() { opacity = opacity > 0.75 ? 0.5 : opacity > 0.4 ? 0.25 : 1; ui(); }},
        {label: "↶", onTap() { c.restore(); }},
        {label: "🗑", onTap() { c.save(); c.clear(); }},
        {label: "📤", onTap() { c.insertImage({transparent: paper === "transparent"}); }},
      ]);
      ui();
    },
  }],
});
