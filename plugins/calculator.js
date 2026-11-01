// Calculator — type a sum and "=": 12*3= becomes 12*3=36.
// Supports + - * / x × ÷ ^ % (as "percent of": 200*15% = 30) and parentheses. No eval(): a small safe parser.
plugin({
  name: "Calculator",
  description: "Type 12*3= and the answer is inserted after the equals sign.",
  settings: {
    decimals: {type: "number", label: "Max decimals", default: 6},
  },

  // Runs just BEFORE the typed character is inserted; ctx.before is the text up to the cursor.
  onText(ch, ctx) {
    if (ch !== "=") return;
    const m = /(-?[0-9.(][0-9.+\-*\/x×÷^%() ]*)$/.exec(ctx.before || "");
    if (!m) return;
    const v = calc(m[1]);
    if (v === null) return;
    keys.commit(fmt(v));
  },
});

function calc(src) {
  const s = src.replace(/×|x/g, "*").replace(/÷/g, "/").replace(/\s+/g, "");
  if (!/[+\-*\/^%]/.test(s)) return null;                 // a lone number is not a sum
  let i = 0;
  const peek = () => s[i];
  function expr() {
    let v = term();
    while (peek() === "+" || peek() === "-") { const op = s[i++]; const r = term(); v = op === "+" ? v + r : v - r; }
    return v;
  }
  function term() {
    let v = power();
    while (peek() === "*" || peek() === "/") {
      const op = s[i++]; const r = power();
      v = op === "*" ? v * r : v / r;
    }
    return v;
  }
  function power() {
    const b = unary();
    if (peek() === "^") { i++; return Math.pow(b, power()); }
    return b;
  }
  function unary() {
    if (peek() === "-") { i++; return -unary(); }
    if (peek() === "+") { i++; return unary(); }
    let v = atom();
    while (peek() === "%") { i++; v = v / 100; }
    return v;
  }
  function atom() {
    if (peek() === "(") {
      i++; const v = expr();
      if (peek() !== ")") throw new Error("paren");
      i++; return v;
    }
    const m = /^[0-9]*\.?[0-9]+|^[0-9]+\.?/.exec(s.slice(i));
    if (!m) throw new Error("num");
    i += m[0].length; return parseFloat(m[0]);
  }
  try {
    const v = expr();
    if (i !== s.length || !isFinite(v)) return null;
    return v;
  } catch (e) { return null; }
}

function fmt(v) {
  const d = Math.max(0, Math.min(10, Number(keys.settings.decimals) || 6));
  return String(parseFloat(v.toFixed(d)));
}
