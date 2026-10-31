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
`word-count` · `clean-paste` (🧹 strips tracking params) · `online-suggest` (Datamuse completions via `keys.http`)
