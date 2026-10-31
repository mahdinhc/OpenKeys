// Mirror of the Kotlin layout DSL (used for the live preview in the editor).
export type PKey = {label: string; weight: number; special: boolean; pad: boolean};

const esc = /\\u\{([0-9a-fA-F]{1,6})\}|\\u([0-9a-fA-F]{4})/g;
export const unescapeDsl = (s: string) =>
  s.replace(esc, (_m, a, b) => String.fromCodePoint(parseInt(a || b, 16)));

const SPECIAL: Record<string, [string, number]> = {
  shift: ['⇧', 1.5], del: ['⌫', 1.5], enter: ['⏎', 1.5], space: ['', 5], sym: ['?123', 1.4],
  sym2: ['=\\<', 1.4], abc: ['ABC', 1.4], lang: ['🌐', 1], emoji: ['☺', 1], mic: ['🎤', 1], pad: ['', 0.5],
};

export function parseRow(row: string): PKey[] {
  return row.trim().split(/\s+/).filter(Boolean).map(raw => {
    const t = unescapeDsl(raw);
    if (t.length > 2 && t.startsWith('{') && t.endsWith('}')) {
      const [head, w] = t.slice(1, -1).split(':');
      const name = head.split('=')[0];
      const sp = SPECIAL[name];
      const weight = w ? parseFloat(w) : sp ? sp[1] : 1.4;
      if (name === 'pipe') return {label: '|', weight: weight || 1, special: false, pad: false};
      if (name === 'eq') return {label: '=', weight: weight || 1, special: false, pad: false};
      if (name === 'layer') { const lp = head.split('=')[1] || '?'; return {label: lp.includes('~') ? lp.split('~')[1] : lp, weight, special: true, pad: false}; }
      if (name === 'stack') return {label: (head.split('=')[1] || '').replace(/;/g, ' '), weight: w ? parseFloat(w) : 1, special: true, pad: false};
      return {label: sp ? sp[0] : t, weight, special: true, pad: name === 'pad'};
    }
    let tt = t, cw = 1;
    const mw = tt.match(/@(\d+(?:\.\d+)?)$/);
    if (mw) { cw = parseFloat(mw[1]); tt = tt.slice(0, mw.index); }
    const head = tt.split('|')[0];
    const label = head.length > 1 && head.includes('=') && !head.startsWith('=') ? head.split('=')[0] : head;
    return {label, weight: cw, special: false, pad: false};
  });
}

export const SYMBOLS = [
  '1 2 3 4 5 6 7 8 9 0',
  '@ # $|€|£|¥|¢|₹|৳ _ & - + ( ) /',
  '{sym2:1.4} * " \' : ; ! ? {del:1.4}',
  '{abc:1.5} , {layer=numpad~1234:1} {space:4.2} . {enter:1.5}',
];
export const SYMBOLS2 = [
  '~ ` {pipe} \u{2022} \u{221A} \u03C0 \u00F7 \u00D7 \u00A7 \u2206',
  '\u00A3 \u00A2 \u20AC \u00A5 ^ \u00B0 {eq} { } \\',
  '{sym:1.4} % \u00A9 \u00AE \u2122 \u2713 [ ] {del:1.4}',
  '{abc:1.5} < {layer=numpad~1234:1} {space:4.2} > {enter:1.5}',
];

export const NEW_LAYOUT = {
  id: 'my_language', name: 'My language', locale: 'en', mode: 'direct', dict: 'none',
  autoCap: false, swipe: false, sentenceEnd: '.',
  layers: {
    base: [
      'q w e r t y u i o p',
      '{pad:0.5} a s d f g h j k l {pad:0.5}',
      '{shift:1.5} z x c v b n m {del:1.5}',
      '{sym:1.5} , {emoji} {space:4.2} . {enter:1.5}',
    ],
    symbols: SYMBOLS,
    symbols2: SYMBOLS2,
  },
};
