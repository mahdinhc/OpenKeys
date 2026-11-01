// Fancy Text — select text, tap 𝔽, pick a style: 𝗯𝗼𝗹𝗱, 𝘪𝘵𝘢𝘭𝘪𝘤, 𝙼𝚘𝚗𝚘, ⓒⓘⓡⓒⓛⓔⓓ, ｆｕｌｌｗｉｄｔｈ, s̶t̶r̶i̶k̶e̶, u̲n̲d̲e̲r̲l̲i̲n̲e̲, ꜱᴍᴀʟʟ ᴄᴀᴘꜱ, ǝpısdn.
// These are Unicode look-alike characters, so they work in any app that can show them.
plugin({
  name: "Fancy Text",
  description: "Select text, tap 𝔽: bold, italic, monospace, circled, fullwidth, strikethrough, underline, small caps, upside down.",
  toolbar: [{
    id: "menu",
    icon: "𝔽",
    run() {
      if (!keys.selected()) return keys.toast("Select some text first");
      keys.panel({
        title: "Fancy text",
        columns: 2,
        items: [
          ["𝗕𝗼𝗹𝗱", (s) => shift(s, 0x1D5D4, 0x1D5EE, 0x1D7EC)],
          ["𝘐𝘵𝘢𝘭𝘪𝘤", (s) => shift(s, 0x1D608, 0x1D622, null)],
          ["𝙗𝙤𝙡𝙙 𝙞𝙩𝙖𝙡𝙞𝙘", (s) => shift(s, 0x1D63C, 0x1D656, null)],
          ["𝙼𝚘𝚗𝚘𝚜𝚙𝚊𝚌𝚎", (s) => shift(s, 0x1D670, 0x1D68A, 0x1D7F6)],
          ["𝓢𝓬𝓻𝓲𝓹𝓽", (s) => shift(s, 0x1D4D0, 0x1D4EA, null)],
          ["Ⓒⓘⓡⓒⓛⓔⓓ", (s) => shift(s, 0x24B6, 0x24D0, null)],
          ["Ｆｕｌｌｗｉｄｔｈ", (s) => shift(s, 0xFF21, 0xFF41, 0xFF10)],
          ["S̶t̶r̶i̶k̶e̶", (s) => combine(s, "\u0336")],
          ["U̲n̲d̲e̲r̲l̲i̲n̲e̲", (s) => combine(s, "\u0332")],
          ["ꜱᴍᴀʟʟ ᴄᴀᴘꜱ", (s) => Array.from(s.toLowerCase()).map((c) => SMALL[c] || c).join("")],
          ["ǝpısdn pɐǝɹ", (s) => Array.from(s.toLowerCase()).map((c) => FLIP[c] || c).reverse().join("")],
        ].map(([label, fn]) => ({label, onTap: () => apply(fn)})),
      });
    },
  }],
});

// A-Z, a-z and (optionally) 0-9 moved to another Unicode block. Letters not in a block are left as typed.
function shift(s, up, low, dig) {
  return Array.from(s).map((c) => {
    const n = c.codePointAt(0);
    if (n >= 65 && n <= 90) return String.fromCodePoint(up + n - 65);
    if (n >= 97 && n <= 122) return String.fromCodePoint(low + n - 97);
    if (dig !== null && n >= 48 && n <= 57) return String.fromCodePoint(dig + n - 48);
    return c;
  }).join("");
}
const combine = (s, mark) => Array.from(s).map((c) => (/\s/.test(c) ? c : c + mark)).join("");

const SMALL = {a: "ᴀ", b: "ʙ", c: "ᴄ", d: "ᴅ", e: "ᴇ", f: "ꜰ", g: "ɢ", h: "ʜ", i: "ɪ", j: "ᴊ", k: "ᴋ", l: "ʟ", m: "ᴍ",
  n: "ɴ", o: "ᴏ", p: "ᴘ", q: "ǫ", r: "ʀ", s: "ꜱ", t: "ᴛ", u: "ᴜ", v: "ᴠ", w: "ᴡ", y: "ʏ", z: "ᴢ"};
const FLIP = {a: "ɐ", b: "q", c: "ɔ", d: "p", e: "ǝ", f: "ɟ", g: "ƃ", h: "ɥ", i: "ı", j: "ɾ", k: "ʞ", l: "l", m: "ɯ", n: "u",
  o: "o", p: "d", q: "b", r: "ɹ", s: "s", t: "ʇ", u: "n", v: "ʌ", w: "ʍ", x: "x", y: "ʎ", z: "z", "?": "¿", "!": "¡", ".": "˙", ",": "'"};

function apply(fn) {
  const s = keys.selected();
  if (!s) return keys.toast("Selection was lost");
  keys.commit(fn(s));
}
