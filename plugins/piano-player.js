// Piano Player — select a piano code (the text the keyboard's piano types), tap 🎹, and it plays it
// Synthesia-style: notes fall onto an on-screen keyboard while the music plays.
//   D#4:50 D#4:100 _:1150 A#4:50 [E4 D#5]:300     note:ms · [chord]:ms · _:ms (rest)   (no :ms = 300 ms)
// If the selection is not a valid piano code you get a toast saying what is wrong.
plugin({
  name: "Piano Player",
  description: "Select a piano code, tap 🎹: it plays with falling notes on a keyboard.",
  settings: {
    lookahead: {type: "number", label: "Seconds of notes shown above the keys", default: 2.5},
    height: {type: "number", label: "Player height (% of keyboard)", default: 170},
  },
  toolbar: [{
    id: "play",
    icon: "🎹",
    run() {
      const text = keys.selected();
      if (!text || !text.trim()) return keys.toast("Select a piano code first");
      const r = parse(text);
      if (!r.ok) return keys.toast("Not a valid piano code: " + r.error);
      open(r);
    },
  }],
  api: {parse: (t) => parse(t)},
});

// ------------------------------------------------------------------ parsing
const SEMI = {C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11};
const WHITE_PC = [0, 2, 4, 5, 7, 9, 11];
const NOTE = /^([A-Ga-g])([#b]?)(-?\d+)$/;

function midiOf(s) {
  const m = NOTE.exec(s);
  if (!m) return null;
  const v = SEMI[m[1].toUpperCase()] + (m[2] === "#" ? 1 : m[2] === "b" ? -1 : 0) + (parseInt(m[3], 10) + 1) * 12;
  return v >= 0 && v <= 127 ? v : null;
}

// Strict: every character of the text must belong to a valid token, otherwise {ok:false, error}.
function parse(text) {
  const src = String(text), n = src.length;
  const bad = (msg) => ({ok: false, error: msg});
  const steps = [];
  let i = 0;
  for (;;) {
    while (i < n && /\s/.test(src[i])) i++;
    if (i >= n) break;
    const start = i;
    if (src[i] === "[") {
      const j = src.indexOf("]", i);
      if (j < 0) return bad('missing "]" after "' + src.slice(i, i + 14) + '"');
      i = j + 1;
    } else {
      while (i < n && !/[\s:\[\]]/.test(src[i])) i++;
      if (i === start) return bad('unexpected "' + src.slice(start, start + 8) + '"');
    }
    const body = src.slice(start, i);
    let ms = 300;
    if (src[i] === ":") {
      i++;
      const ds = i;
      while (i < n && /\d/.test(src[i])) i++;
      if (i === ds) return bad('duration missing after ":" in "' + body + '"');
      ms = parseInt(src.slice(ds, i), 10);
      if (ms < 1 || ms > 60000) return bad('duration ' + ms + ' ms out of range in "' + body + '"');
    }
    if (i < n && !/\s/.test(src[i])) return bad('unexpected "' + src.slice(i, i + 8) + '" after "' + body + '"');
    let notes = [];
    if (body === "_") notes = [];
    else if (body[0] === "[") {
      const parts = body.slice(1, -1).trim().split(/\s+/).filter(Boolean);
      if (!parts.length) return bad("empty chord []");
      for (const p of parts) {
        const m = midiOf(p);
        if (m === null) return bad('"' + p + '" is not a note (use C4, D#4, Bb3 ...)');
        if (notes.indexOf(m) < 0) notes.push(m);
      }
    } else {
      const m = midiOf(body);
      if (m === null) return bad('"' + body.slice(0, 12) + '" is not a note, chord or _');
      notes = [m];
    }
    steps.push({notes: notes, ms: Math.max(20, ms)});
  }
  if (!steps.length) return bad("empty");
  if (!steps.some((s) => s.notes.length)) return bad("only rests, no notes");
  // timeline: a note sounds for 94% of its step (like the keyboard's own player), rests just advance time
  const events = [];
  let t = 0;
  for (const s of steps) {
    const hold = s.notes.length ? s.ms * 0.94 : s.ms;
    for (const m of s.notes) events.push({midi: m, on: t, off: t + hold});
    t += s.ms;
  }
  return {ok: true, steps: steps, events: events, total: t};
}

// ------------------------------------------------------------------ player
const SPEEDS = [0.5, 0.75, 1, 1.5, 2];
const BG = "#0b0e16";

function hue(midi) {                                   // one colour per pitch class
  const h = (midi % 12) * 30, s = 0.78, l = 0.58;
  const a = s * Math.min(l, 1 - l);
  const f = (n) => {
    const k = (n + h / 30) % 12;
    const c = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return ("0" + Math.round(c * 255).toString(16)).slice(-2);
  };
  return "#" + f(0) + f(8) + f(4);
}

function open(song) {
  const ev = song.events, total = song.total;
  const offOrder = ev.slice().sort((a, b) => a.off - b.off);
  const L = Math.max(0.8, Math.min(8, Number(keys.settings.lookahead) || 2.5)) * 1000;

  // keyboard range: from the lowest to the highest note, padded to white keys, at least 15 white keys
  let lo = Math.min.apply(null, ev.map((e) => e.midi)), hi = Math.max.apply(null, ev.map((e) => e.midi));
  const isWhite = (m) => WHITE_PC.indexOf(m % 12) >= 0;
  while (!isWhite(lo)) lo--;
  while (!isWhite(hi)) hi++;
  const whites = () => { const w = []; for (let m = lo; m <= hi; m++) if (isWhite(m)) w.push(m); return w; };
  lo = Math.max(0, lo - 1); hi = Math.min(127, hi + 1);
  while (!isWhite(lo)) lo--;
  while (!isWhite(hi)) hi++;
  while (whites().length < 15 && (lo > 0 || hi < 127)) { if (lo > 0) { lo--; while (lo > 0 && !isWhite(lo)) lo--; } if (whites().length < 15 && hi < 127) { hi++; while (hi < 127 && !isWhite(hi)) hi++; } }
  const W = whites(), widx = {};
  W.forEach((m, k) => { widx[m] = k; });

  let w = 0, h = 0, ww = 0, kbTop = 0, kbH = 0;
  let playing = true, songT = 0, speed = 1, onIdx = 0, offIdx = 0, dragging = false;
  const down = {};                                      // midi -> how many notes are sounding

  const c = keys.canvas({
    title: "Piano player",
    background: BG,
    height: Number(keys.settings.height) || 170,
    buttons: btns(),
    onResize(cw, ch) { w = cw; h = ch; layout(); c.fillAll(BG); drawKeys(); },
    onTouch(e) {                                        // tap / drag along the top strip to seek
      if (e.type === "down" && e.y < 22) dragging = true;
      if (dragging && w > 0 && (e.type === "down" || e.type === "move")) seek(e.x / w * total);
      if (e.type === "up" || e.type === "cancel") dragging = false;
    },
    onClose() { stopAudio(); },
  });
  keys.audio.start();
  c.animate(frame, 40);
  keys.toast("▶ " + ev.length + " notes · " + fmt(total));

  function layout() {
    ww = w / W.length;
    kbH = Math.max(36, h * 0.24);
    kbTop = h - kbH;
  }

  function btns() {
    return [
      {label: playing ? "⏸ Pause" : (songT >= total ? "↻ Replay" : "▶ Play"), active: playing, onTap() { toggle(); }},
      {label: "⏮", onTap() { seek(0); }},
      {label: "×" + speed, onTap() { speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length]; c.buttons(btns()); }},
      {label: "✖ Close", onTap() { stopAudio(); c.close(); }},
    ];
  }

  function toggle() {
    if (songT >= total) { seek(0); playing = true; }
    else playing = !playing;
    if (!playing) allOff();
    c.buttons(btns());
  }

  function press(m, d) {
    const was = down[m] || 0, now = Math.max(0, was + d);
    down[m] = now;
    return (was > 0) !== (now > 0);
  }

  function allOff() {
    keys.audio.stop();
    for (const m in down) down[m] = 0;
    drawKeys();
  }

  function stopAudio() { keys.audio.stop(); playing = false; }

  function seek(t) {
    keys.audio.stop();
    songT = Math.max(0, Math.min(total, t));
    onIdx = 0; while (onIdx < ev.length && ev[onIdx].on < songT) onIdx++;
    offIdx = 0; while (offIdx < offOrder.length && offOrder[offIdx].off <= songT) offIdx++;
    for (const m in down) down[m] = 0;
    for (const e of ev) if (e.on <= songT && e.off > songT) down[e.midi] = (down[e.midi] || 0) + 1;   // shown, not sounded
    drawKeys();
    c.buttons(btns());
  }

  function frame(dt) {
    if (w <= 0) return;
    if (playing) {
      songT += dt * speed;
      let changed = false;
      // handle note-ons and note-offs in time order (offs first on a tie) so a repeated pitch is released before it is struck again
      for (;;) {
        const nOn = onIdx < ev.length ? ev[onIdx].on : Infinity, nOff = offIdx < offOrder.length ? offOrder[offIdx].off : Infinity;
        if (Math.min(nOn, nOff) > songT) break;
        if (nOff <= nOn) { const m = offOrder[offIdx++].midi; keys.audio.off(m); if (press(m, -1)) changed = true; }
        else { const m = ev[onIdx++].midi; keys.audio.on(m); if (press(m, 1)) changed = true; }
      }
      if (changed) drawKeys();
      if (songT >= total + 250) { songT = total; playing = false; allOff(); c.buttons(btns()); }
    }
    drawFall();
  }

  // ---- drawing
  function keyX(m) {                                    // returns [x, width, isBlack]
    if (isWhite(m)) return [widx[m] * ww, ww, false];
    const left = widx[m - 1];
    return [(left + 1) * ww - ww * 0.3, ww * 0.6, true];
  }

  function drawKeys() {
    if (w <= 0) return;
    for (const m of W) {
      const on = (down[m] || 0) > 0;
      c.rect(widx[m] * ww + 0.5, kbTop, ww - 1, kbH, {fill: on ? hue(m) : "#eef0f5", radius: 3});
    }
    for (let m = lo; m <= hi; m++) {
      if (isWhite(m) || widx[m - 1] === undefined || widx[m + 1] === undefined) continue;
      const on = (down[m] || 0) > 0, k = keyX(m);
      c.rect(k[0], kbTop, k[1], kbH * 0.62, {fill: on ? hue(m) : "#171a22", radius: 2});
    }
  }

  function drawFall() {
    c.rect(0, 0, w, kbTop, {fill: BG});
    for (const m of W) if (m % 12 === 0) c.line(widx[m] * ww, 0, widx[m] * ww, kbTop, {color: "#ffffff16", width: 1});
    const y = (t) => kbTop - (t - songT) / L * kbTop;
    for (const e of ev) {
      if (e.on > songT + L) break;                      // events are sorted by start time
      if (e.off < songT) continue;
      const yb = y(e.on), yt = Math.max(0, y(e.off));
      if (yb < 0) continue;
      const k = keyX(e.midi), active = e.on <= songT;
      c.rect(k[0] + 1, yt, k[1] - 2, Math.max(4, Math.min(yb, kbTop) - yt), {
        fill: hue(e.midi), radius: 3, alpha: active ? 1 : 0.82,
        stroke: active ? "#ffffff" : undefined, width: active ? 1.5 : 0,
      });
    }
    c.rect(0, 0, w, 3, {fill: "#ffffff22"});
    c.rect(0, 0, w * (total ? songT / total : 0), 3, {fill: "#7aa2ff"});
    c.text(fmt(songT) + " / " + fmt(total), w - 6, 17, {size: 11, color: "#9aa3b5", align: "right"});
  }
}

function fmt(ms) {
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + ":" + ("0" + (s % 60)).slice(-2);
}
