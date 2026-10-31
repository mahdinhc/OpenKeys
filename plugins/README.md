# OpenKeys plugins

A plugin is **one `.js` file**. No build step, no manifest. Add it in the app (**Plugins** tab → *New plugin*, *Install from URL*,
or *Samples*) and it is live immediately. The file name is the plugin id (`camel-case.js` → `camel-case`).

```js
plugin({
  name: "Camel Case",
  toolbar: [{ icon: "🐫", run() { keys.commit(toCamel(keys.selected())); } }],
});
```

The samples in this folder are bundled into the app (`scripts/bootstrap.sh` copies `plugins/*.js` into the APK assets).
Test them without a phone: `node scripts/test_plugins.js`.

## `plugin({...})`

| field | what it does |
|---|---|
| `name`, `description`, `version` | shown in the app |
| `settings` | `{ key: {type, label, default, hint?, options?} }` — the app renders a form; read values from `keys.settings.key`. Types: `text`, `textarea`, `number`, `bool`, `select` (with `options: [...]`) |
| `toolbar` | `[{icon, run(ctx), id?}]` — buttons on the top bar (shown when no suggestions are visible) |
| `actions` | same shape, but **not** on the toolbar: only reachable as layout keys |
| `onCommit(word, ctx)` | **sync.** Runs when a typed word is about to be committed (space/punctuation). Return a string to replace the word, nothing to keep it. Must finish in ~30 ms or it is skipped |
| `onText(char, ctx)` | async. Runs just *before* a character is inserted (`ctx.before` does not include it yet) |
| `suggest(ctx, done)` | async. Return an array, or call `done([...])` later (a Promise also works). Up to 3 words appear after the typed word. Debounce network calls yourself |
| `onStartInput(ctx)` | async. A text field got focus (`ctx.package`, `ctx.inputType`) |
| `onSelection(ctx, start, end)` · `onFinishInput(ctx)` · `onLoad()` | async. Cursor moved · field closed · all plugins loaded |
| `api` | object other plugins reach with `keys.use(id)` |

`ctx` = `{ word, prev, before, package, inputType, layout, locale, noSuggest, secure }` and, for toolbar/actions/panels,
also `{ after, selected }`. `before`/`after` are up to 100 characters around the cursor. `ctx.secure` is true in password fields.

### Layout keys
Any toolbar item or action can be a key in a layout: `{js=<pluginId>.<actionId>~Label}` (label optional; defaults to the icon).
Give the item an `id` (otherwise the first one is `run`, then `run2`, …): `toolbar: [{id: "camel", icon: "🐫", run}]` → `{js=camel-case.camel}`.

## `keys` (global)

**Write text** (async, applied in order): `commit(text, back?)` (`back` moves the cursor back N chars afterwards — handy for
auto-closing brackets; committing while text is selected replaces the selection) · `deleteBefore(n)` · `deleteAfter(n)` ·
`replaceBefore(n, text)` · `sendKey("enter"|"del"|"tab"|"left"|"right"|"up"|"down"|"home"|"end"|"esc")` · `moveCursor(n)` ·
`selectAll()` `copy()` `cut()` `paste()`

**Read text** (snapshot from when the hook/action started): `selected()` · `before(n?)` · `after(n?)` · `word()` · `ctx()`

**UI:** `toast(text)` · `haptic()` · `switchLayout(id)` · `closePanel()` ·
`panel({title, columns, keepOpen, items})` — items are `"text"` (insert it), `{label, text}` (insert `text`),
or `{label, onTap() {...}}`. The panel closes after a tap unless `keepOpen: true`.

**Data:** `settings.*` · `storage.get(key, default)` / `storage.set(key, value)` / `storage.remove(key)` (JSON values, per plugin) ·
`clipboard.get()` / `clipboard.set(text)`

**Network:** `keys.http(url, {method, headers, body, timeout}, (err, res) => {...})` with `res = {ok, status, body, json()}`
(object bodies are sent as JSON). Also global `fetch` (Promise-based) — see the note below.

**Globals:** `console.log/warn/error` (→ Plugins → *Show log*), `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval`.

## Canvas — `keys.canvas(spec)`
A drawing surface that replaces the keyboard (like `keys.panel`). Ops are **batched** and painted onto a **persistent bitmap**, so you
only send what changes. Coordinates and sizes are **dp**; colors are `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgba(r,g,b,a)`, CSS names, or a **gradient**.
Returns `c`. See `plugins/draw.js`.

```js
const c = keys.canvas({
  title: "Demo", background: "#fff",
  height: 150,                 // % of the keyboard height (50-260, default 100)
  toolbar: true,               // false hides the button row (full-screen canvas; the ABC key stays)
  buttons: [{label: "Clear", onTap() { c.clear(); }}],       // scrolling button row; c.buttons([...]) updates it (active: true highlights)
  onResize(w, h) { /* size known / changed; c.width, c.height */ },
  onTouch(e) { /* e = {type: "down"|"move"|"up"|"cancel", id, x, y, pressure, stylus}  (multi-touch: one event per pointer id) */ },
  onClose() { /* user left the canvas */ },
});
```

**Shapes** — `o` = options object, always optional
| call | |
|---|---|
| `c.line(x1,y1,x2,y2,o)` `c.rect(x,y,w,h,o)` `c.circle(x,y,r,o)` `c.oval(x,y,w,h,o)` `c.dot(x,y,o)` | basics (`rect`: `o.radius`) |
| `c.arc(x,y,w,h,startDeg,sweepDeg,o)` | arc of the oval in that box; `o.pie` = wedge |
| `c.bezier(x0,y0,cx,cy,x1,y1,o)` / `c.bezier(x0,y0,c1x,c1y,c2x,c2y,x1,y1,o)` | quadratic / cubic curve |
| `c.path(points,o)` | `[[x,y],...]`, `[{x,y},...]` or flat; `o.smooth`, `o.closed` |
| `c.polygon(cx,cy,r,sides,o)` `c.star(cx,cy,r1,r2,points,o)` | `o.rotation` in degrees |
| `c.text(str,x,y,o)` | `o.size, color\|fill, stroke, align, bold, italic, font:"sans\|serif\|mono"` (y = baseline) · `c.measureText(str,size,o)` → width |
| `c.clearRect(x,y,w,h)` `c.fillAll(color)` `c.clear()` `c.background(color)` `c.grid(step)` | |

**Options `o`:** `color`/`stroke`, `fill`, `width`, `alpha` (0-1), `dash: [on, off]`, `cap` (`round\|butt\|square`), `join` (`round\|miter\|bevel`),
`shadow: {blur, dx, dy, color}`, `erase: true` (cuts transparent holes), `blend` (`multiply screen overlay darken lighten add xor in out atop`).
`c.defaults = {color, width, size}` are used when `o` omits them.

**Gradients** (use as `fill`, `stroke` or text `color`): `c.linearGradient(x1,y1,x2,y2,stops)` · `c.radialGradient(x,y,r,stops)` · `c.sweepGradient(x,y,stops)`;
`stops` = `["#f00","#00f"]` (even) or `[[0,"#f00"],[0.3,"#ff0"],[1,"#00f"]]`.

**Transforms & clipping** (apply to everything drawn afterwards): `c.translate(x,y)` · `c.rotate(deg[,px,py])` · `c.scale(sx[,sy[,px,py]])` · `c.resetTransform()` ·
`c.push()` / `c.pop()` (transform + clip) · `c.clipRect(x,y,w,h)` · `c.clipCircle(x,y,r)` · `c.clipReset()`.

**Pixels & images:** `c.flood(x,y,color,tolerance=24)` bucket fill · `c.image(src,x,y,w?,h?,{alpha})` (`data:` URL, raw base64, or `https://` URL) ·
`c.save()` / `c.restore()` bitmap snapshots (30) = cheap **undo** · `c.snapshot(cb,{max,transparent})` → `cb(dataUrl)` PNG ·
`c.insertImage({transparent})` → PNG into the text field (copied to the clipboard if the app refuses images).

**Lifecycle:** `c.title(t)` `c.buttons([...])` `c.toolbar(bool)` · `c.animate((dtMs, c) => {...}, fps=30)` → returns a stop function (stops itself on close) ·
`c.flush()` (ops flush automatically when your callback returns; only needed inside long loops) · `c.close()` · `c.closed`.
Only one canvas is open at a time; opening another (or a panel) calls the old one's `onClose`.

## Plugins talking to each other
* `plugin({ api: {...} })` + `keys.use("other-id")` → that plugin's `api` (or `null`). Load order is alphabetical, so call `keys.use` at run time or in `onLoad`.
* `onLoad()` hook: runs once after **all** plugins are loaded.
* `keys.on(event, fn)` · `keys.off(event, fn?)` · `keys.emit(event, data)` — event bus across plugins.
* `keys.run("plugin.action")` — run another action (toolbar/actions entry) directly.

## More API
* `keys.sendKey(name, {ctrl, alt, shift, meta})` — now also letters/digits/`f1…f12`/`space`/`pageup`/`pagedown`: `keys.sendKey("a", {ctrl: true})`.
* `keys.replaceAll(text)` — replace the whole field. `keys.suggest([...])` — show suggestions right now (up to 5).
* `keys.theme()` → `{bg, key, keyText, special, accent, accentText, hint}` (current keyboard colors, `#RRGGBB`) — match your UI to the theme.
* `keys.vibrate(ms)` · `keys.openUrl(url)` · `keys.storage.keys()` · `keys.storage.clear()`.
* Hooks: `onSelection(ctx, start, end)` (cursor/selection moved) · `onFinishInput(ctx)` (field lost focus) · `onLoad()`.

## Behaviour worth knowing

* All plugins share one JS engine on a background thread. Typing never waits on it, except `onCommit` (30 ms cap).
* Any save/enable/settings change reloads the engine (a fresh start also recovers from an infinite loop).
* An error in one plugin is logged and doesn't affect the others. Errors in toolbar actions also show as a toast.
* Not available: DOM, `require`/`import`, `URL`, `localStorage`. Use `keys.storage`.
* `async/await` and `fetch()` depend on the QuickJS binding running pending Promise jobs. Callback style (`keys.http`,
  `done(...)`, `setTimeout`) always works, so the samples use it.
* Plugins can see and change everything you type. Only install code you trust.

## Samples
`hello` (template) · `camel-case` (🐫 button) · `case-tools` (Aa panel: UPPER/lower/Title/snake/kebab/camel/Pascal) ·
`text-expander` (`zzdate`, `zztime`, `zzuuid`, `zzsig`) · `auto-pair` (brackets/quotes) · `snippets` (📝 panel) ·
`word-count` · `clean-paste` (🧹 strips tracking params) · `online-suggest` (Datamuse completions via `keys.http`) · `draw` (🎨 drawing pad built on `keys.canvas`)
