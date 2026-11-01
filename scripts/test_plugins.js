#!/usr/bin/env node
// Tests the JS side of the plugin system (native/assets/plugin_prelude.js + plugins/*.js) in plain Node.
// The Kotlin host is replaced by a mock that records every __host.call(...).   Run: node scripts/test_plugins.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const root = path.join(__dirname, '..');
const prelude = fs.readFileSync(path.join(root, 'native/assets/plugin_prelude.js'), 'utf8');
const pluginDir = path.join(root, 'plugins');

// Same wrapper the Kotlin host builds (PluginHost.kt) - keep in sync.
const wrap = (id, code) =>
  '__defs[' + JSON.stringify(id) + ']=function(plugin,keys,console,fetch,setTimeout,setInterval,clearTimeout,clearInterval){' +
  code + '\n};';

function makeRuntime(files, settings = {}) {
  const calls = [];
  const store = {};
  const pendingTimers = [];
  const pendingFetches = [];
  const sandbox = {
    __host: {
      call(fn, json) {
        const o = json ? JSON.parse(json) : {};
        calls.push({fn, o});
        if (fn === 'storeGet') return store[o.plugin + ':' + o.key] || '';
        if (fn === 'storeSet') store[o.plugin + ':' + o.key] = o.value;
        if (fn === 'clipGet') return sandbox.__clip || '';
        if (fn === 'timer') pendingTimers.push(o);
        if (fn === 'fetch') pendingFetches.push(o);
        return '';
      },
    },
  };
  vm.createContext(sandbox);
  vm.runInContext(prelude, sandbox, {filename: 'plugin_prelude.js'});
  for (const [id, code] of Object.entries(files)) {
    vm.runInContext(wrap(id, code), sandbox, {filename: id + '.js'});
    vm.runInContext('__run(' + JSON.stringify(id) + ',' + JSON.stringify(JSON.stringify(settings[id] || {})) + ')', sandbox);
  }
  const rt = {
    calls, store, sandbox, pendingTimers, pendingFetches,
    dispatch(hook, obj) {
      const r = sandbox.__bridge.dispatch(hook, typeof obj === 'string' ? obj : JSON.stringify(obj === undefined ? {} : obj));
      return r ? JSON.parse(r) : null;
    },
    meta() { return rt.dispatch('__meta', ''); },
    take(fn) { const out = calls.filter((c) => c.fn === fn).map((c) => c.o); calls.length = 0; return out; },
    reset() { calls.length = 0; },
  };
  return rt;
}

const extraDir = path.join(pluginDir, 'extras'), tplDir = path.join(pluginDir, 'templates');
const load = (id) => fs.readFileSync(path.join(fs.existsSync(path.join(pluginDir, id + '.js')) ? pluginDir : extraDir, id + '.js'), 'utf8');
let passed = 0;
const test = (name, fn) => { fn(); passed++; console.log('  ok  ' + name); };

// ---------------------------------------------------------------- every sample loads cleanly
const listIds = (d) => fs.readdirSync(d).filter((f) => f.endsWith('.js')).map((f) => f.slice(0, -3));
const dup = listIds(pluginDir).filter((id) => listIds(extraDir).includes(id));
if (dup.length) console.log('  WARNING: ' + dup.join(', ') + ' exist in plugins/ AND plugins/extras/ - delete the plugins/ copies, they would be shipped in the app');
const ids = [...new Set([...listIds(pluginDir), ...listIds(extraDir)])];     // one entry per plugin id
test('all ' + ids.length + ' sample plugins load without errors', () => {
  const files = {};
  ids.forEach((id) => { files[id] = load(id); });
  const rt = makeRuntime(files);
  const meta = rt.meta();
  assert.strictEqual(meta.plugins.length, ids.length);
  meta.plugins.forEach((p) => assert.ok(!p.error, p.id + ' failed: ' + p.error));
  const errs = rt.calls.filter((c) => c.fn === 'log' && c.o.level === 'error');
  assert.deepStrictEqual(errs, []);
});

test('every bootstrap template loads cleanly', () => {
  const files = {};
  fs.readdirSync(tplDir).filter((f) => f.endsWith('.js')).forEach((f) => { files['t-' + f.replace(/^\d+-|\.js$/g, '')] = fs.readFileSync(path.join(tplDir, f), 'utf8'); });
  const meta = makeRuntime(files).meta();
  assert.strictEqual(meta.plugins.length, Object.keys(files).length);
  meta.plugins.forEach((p) => assert.ok(!p.error, p.id + ' failed: ' + p.error));
});

// ---------------------------------------------------------------- calculator
test('calculator: solves the sum before "=", ignores plain text and bad input', () => {
  const rt = makeRuntime({calculator: load('calculator')});
  const eq = (before) => { rt.reset(); rt.dispatch('onText', {text: '=', ctx: {before}}); return rt.take('commit'); };
  assert.deepStrictEqual(eq('12*3'), [{text: '36', back: 0}]);
  assert.deepStrictEqual(eq('call me at 2 + 3 x 4'), [{text: '14', back: 0}]);
  assert.deepStrictEqual(eq('(1+2)*3^2'), [{text: '27', back: 0}]);
  assert.deepStrictEqual(eq('200*15%'), [{text: '30', back: 0}]);
  assert.deepStrictEqual(eq('10/4'), [{text: '2.5', back: 0}]);
  assert.deepStrictEqual(eq('1/3'), [{text: '0.333333', back: 0}]);
  assert.deepStrictEqual(eq('-5+2'), [{text: '-3', back: 0}]);
  assert.strictEqual(eq('7').length, 0);            // a lone number
  assert.strictEqual(eq('hello').length, 0);
  assert.strictEqual(eq('1/0').length, 0);          // infinity
  assert.strictEqual(eq('2+').length, 0);           // incomplete
  assert.strictEqual(eq('(2+3').length, 0);
  rt.reset(); rt.dispatch('onText', {text: 'a', ctx: {before: '2+2'}});
  assert.strictEqual(rt.take('commit').length, 0);  // only "=" triggers it
});

// ---------------------------------------------------------------- panel plugins on a selection
const panelTap = (id, selected, label) => {
  const rt = makeRuntime({[id]: load(id)});
  rt.reset();
  rt.dispatch('action', {key: id + '.menu', ctx: {selected}});
  const panel = rt.take('panel')[0];
  const labels = panel.spec.items.map((i) => i.label);
  const idx = labels.findIndex((l) => l === label);
  assert.ok(idx >= 0, label + ' not in ' + labels.join(' | '));
  rt.dispatch('panel', {plugin: id, index: idx, ctx: {selected}});
  return rt.take('commit')[0].text;
};
test('text-tools: line and text operations', () => {
  assert.strictEqual(panelTap('text-tools', 'b\na\nc', 'Sort lines A→Z'), 'a\nb\nc');
  assert.strictEqual(panelTap('text-tools', 'b\na\nc', 'Sort lines Z→A'), 'c\nb\na');
  assert.strictEqual(panelTap('text-tools', 'a\nb\na\nb\nc', 'Remove duplicate lines'), 'a\nb\nc');
  assert.strictEqual(panelTap('text-tools', 'one\ntwo', 'Reverse line order'), 'two\none');
  assert.strictEqual(panelTap('text-tools', 'abc', 'Reverse text'), 'cba');
  assert.strictEqual(panelTap('text-tools', '  a   b  \n\n\n\n c ', 'Clean spaces'), 'a b\n\nc');
  assert.strictEqual(panelTap('text-tools', 'a\nb', 'Remove line breaks'), 'a b');
  assert.strictEqual(panelTap('text-tools', 'x\n- y', '• Bullet list'), '• x\n• y');
  assert.strictEqual(panelTap('text-tools', 'x\n\ny', '1. Numbered list'), '1. x\n2. y');
  assert.strictEqual(panelTap('text-tools', 'a b&c', 'URL encode'), 'a%20b%26c');
  assert.strictEqual(panelTap('text-tools', 'a%20b%26c', 'URL decode'), 'a b&c');
  assert.strictEqual(panelTap('text-tools', 'café', 'Remove accents'), 'cafe');
});
test('fancy-text: styles transform letters and keep the rest', () => {
  assert.strictEqual(panelTap('fancy-text', 'Hi 5!', '𝗕𝗼𝗹𝗱'), '𝗛𝗶 𝟱!');
  assert.strictEqual(panelTap('fancy-text', 'ab', 'Ⓒⓘⓡⓒⓛⓔⓓ'), 'ⓐⓑ');
  assert.strictEqual(panelTap('fancy-text', 'Ab 1', 'Ｆｕｌｌｗｉｄｔｈ'), 'Ａｂ １');
  assert.strictEqual(panelTap('fancy-text', 'ab', 'S̶t̶r̶i̶k̶e̶'), 'a\u0336b\u0336');
  assert.strictEqual(panelTap('fancy-text', 'ab', 'ꜱᴍᴀʟʟ ᴄᴀᴘꜱ'), 'ᴀʙ');
  assert.strictEqual(panelTap('fancy-text', 'up', 'ǝpısdn pɐǝɹ'), 'dn');
});
test('quick-search: opens the chosen site with the encoded selection', () => {
  const rt = makeRuntime({'quick-search': load('quick-search')});
  rt.reset();
  rt.dispatch('action', {key: 'quick-search.menu', ctx: {selected: 'cat & dog'}});
  const panel = rt.take('panel')[0];
  const i = panel.spec.items.findIndex((x) => x.label === 'Google');
  rt.dispatch('panel', {plugin: 'quick-search', index: i, ctx: {selected: 'cat & dog'}});
  assert.deepStrictEqual(rt.take('openUrl'), [{url: 'https://www.google.com/search?q=cat%20%26%20dog'}]);
  rt.reset();
  rt.dispatch('action', {key: 'quick-search.menu', ctx: {selected: ''}});
  assert.strictEqual(rt.take('panel').length, 0);
});
test('symbols: category panel opens a symbol panel', () => {
  const rt = makeRuntime({symbols: load('symbols')});
  rt.reset();
  rt.dispatch('action', {key: 'symbols.menu', ctx: {}});
  const p1 = rt.take('panel')[0];
  assert.ok(p1.spec.items.length >= 5);
  rt.dispatch('panel', {plugin: 'symbols', index: 1, ctx: {}});
  rt.runTimers && rt.runTimers();
});

// ---------------------------------------------------------------- camel-case
test('camel-case: button converts selection', () => {
  const rt = makeRuntime({'camel-case': load('camel-case')});
  const m = rt.meta().plugins[0];
  assert.deepStrictEqual(m.actions, [{key: 'camel-case.run', icon: '🐫', toolbar: true}]);
  rt.reset();
  rt.dispatch('action', {key: 'camel-case.run', ctx: {selected: 'hello world_foo-bar'}});
  assert.deepStrictEqual(rt.take('commit'), [{text: 'helloWorldFooBar', back: 0}]);
  rt.dispatch('action', {key: 'camel-case.run', ctx: {selected: 'alreadyCamel Case'}});
  assert.deepStrictEqual(rt.take('commit'), [{text: 'alreadyCamelCase', back: 0}]);
});

test('camel-case: nothing selected -> toast, no commit', () => {
  const rt = makeRuntime({'camel-case': load('camel-case')});
  rt.reset();
  rt.dispatch('action', {key: 'camel-case.run', ctx: {selected: ''}});
  assert.strictEqual(rt.take('commit').length, 0);
});

// ---------------------------------------------------------------- case-tools (panel + onTap)
test('case-tools: panel opens, tapping an item transforms selection', () => {
  const rt = makeRuntime({'case-tools': load('case-tools')});
  rt.reset();
  rt.dispatch('action', {key: 'case-tools.menu', ctx: {selected: 'hello big world'}});
  const panel = rt.take('panel')[0];
  assert.strictEqual(panel.plugin, 'case-tools');
  const labels = panel.spec.items.map((i) => i.label);
  assert.ok(labels.includes('snake_case') && labels.includes('PascalCase'));
  const idx = (l) => labels.indexOf(l);
  const tap = (l) => { rt.dispatch('panel', {plugin: 'case-tools', index: idx(l), ctx: {selected: 'hello big world'}}); return rt.take('commit')[0].text; };
  assert.strictEqual(tap('UPPERCASE'), 'HELLO BIG WORLD');
  assert.strictEqual(tap('Title Case'), 'Hello Big World');
  assert.strictEqual(tap('snake_case'), 'hello_big_world');
  assert.strictEqual(tap('kebab-case'), 'hello-big-world');
  assert.strictEqual(tap('camelCase'), 'helloBigWorld');
  assert.strictEqual(tap('PascalCase'), 'HelloBigWorld');
  assert.strictEqual(tap('Sentence case'), 'Hello big world');
});

// ---------------------------------------------------------------- text-expander (sync hook)
test('text-expander: expands triggers, ignores normal words, honours settings', () => {
  const rt = makeRuntime({'text-expander': load('text-expander')}, {'text-expander': {signature: 'Bye', prefix: 'zz'}});
  assert.deepStrictEqual(rt.meta().plugins[0].hooks, ['onCommit']);
  assert.strictEqual(rt.dispatch('onCommit', {word: 'hello', ctx: {}}), null);
  assert.strictEqual(rt.dispatch('onCommit', {word: 'zzsig', ctx: {}}).text, 'Bye');
  assert.match(rt.dispatch('onCommit', {word: 'ZZdate', ctx: {}}).text, /^\d{4}-\d{2}-\d{2}$/);
  assert.match(rt.dispatch('onCommit', {word: 'zztime', ctx: {}}).text, /^\d{2}:\d{2}$/);
  assert.match(rt.dispatch('onCommit', {word: 'zzuuid', ctx: {}}).text, /^[0-9a-f-]{36}$/);
  assert.strictEqual(rt.dispatch('onCommit', {word: 'zzunknown', ctx: {}}), null);
});

test('settings defaults apply when nothing is stored', () => {
  const rt = makeRuntime({'text-expander': load('text-expander')});
  assert.strictEqual(rt.dispatch('onCommit', {word: 'zzsig', ctx: {}}).text, 'Cheers');
});

// ---------------------------------------------------------------- auto-pair (onText)
test('auto-pair: inserts closer and moves cursor back', () => {
  const rt = makeRuntime({'auto-pair': load('auto-pair')});
  rt.reset();
  rt.dispatch('onText', {text: '(', ctx: {before: 'foo'}});
  assert.deepStrictEqual(rt.take('commit'), [{text: ')', back: 1}]);
  rt.dispatch('onText', {text: 'a', ctx: {before: 'foo'}});
  assert.strictEqual(rt.take('commit').length, 0);
  rt.dispatch('onText', {text: '"', ctx: {before: 'say '}});
  assert.deepStrictEqual(rt.take('commit'), [{text: '"', back: 1}]);
  rt.dispatch('onText', {text: "'", ctx: {before: 'don'}});          // apostrophe, not a quote
  assert.strictEqual(rt.take('commit').length, 0);
});

// ---------------------------------------------------------------- snippets (settings -> panel text items)
test('snippets: builds panel from settings', () => {
  const rt = makeRuntime({snippets: load('snippets')});
  rt.reset();
  rt.dispatch('action', {key: 'snippets.run', ctx: {}});
  const p = rt.take('panel')[0];
  assert.strictEqual(p.spec.columns, 2);
  assert.deepStrictEqual(p.spec.items[0], {label: 'Email', text: 'me@example.com'});
  assert.strictEqual(p.spec.items[1].text, 'Best regards,\nMe');
});

// ---------------------------------------------------------------- word-count
test('word-count: toast with counts', () => {
  const rt = makeRuntime({'word-count': load('word-count')});
  rt.reset();
  rt.dispatch('action', {key: 'word-count.run', ctx: {selected: 'one two\nthree'}});
  assert.strictEqual(rt.take('toast')[0].text, '13 chars · 3 words · 2 lines');
});

// ---------------------------------------------------------------- clean-paste (clipboard)
test('clean-paste: tidies clipboard and strips tracking params', () => {
  const rt = makeRuntime({'clean-paste': load('clean-paste')});
  rt.sandbox.__clip = '  see   https://x.com/a?utm_source=t&id=5&fbclid=z#top  \n\n\n\nbye ';
  rt.reset();
  rt.dispatch('action', {key: 'clean-paste.run', ctx: {}});
  assert.strictEqual(rt.take('commit')[0].text, 'see https://x.com/a?id=5#top\n\nbye');
});

// ---------------------------------------------------------------- online-suggest (timer + http + done)
test('online-suggest: debounced timer -> http -> suggest result', () => {
  const rt = makeRuntime({'online-suggest': load('online-suggest')});
  rt.reset();
  rt.dispatch('suggest', {seq: 7, ctx: {word: 'he'}});                 // too short
  assert.strictEqual(rt.pendingTimers.length, 0);
  rt.dispatch('suggest', {seq: 8, ctx: {word: 'hel'}});
  rt.dispatch('suggest', {seq: 9, ctx: {word: 'hell'}});
  assert.strictEqual(rt.pendingTimers.length, 2);
  assert.strictEqual(rt.take('clearTimer').length, 1);                   // first timer was cancelled by the second keystroke
  rt.dispatch('__timer', String(rt.pendingTimers[1].id));
  const f = rt.pendingFetches[0];
  assert.ok(f.url.endsWith('s=hell'));
  rt.reset();
  rt.dispatch('__fetch', {id: f.id, ok: true, status: 200, text: '[{"word":"hello"},{"word":"hellfire"}]'});
  assert.deepStrictEqual(rt.take('suggest'), [{seq: 9, plugin: 'online-suggest', list: ['hello', 'hellfire']}]);
});

// ---------------------------------------------------------------- runtime behaviour
test('storage roundtrip and per-plugin isolation', () => {
  const code = 'plugin({name:"S", toolbar:[{run(){ keys.storage.set("n",(keys.storage.get("n",0))+1); keys.toast(String(keys.storage.get("n"))); }}]});';
  const rt = makeRuntime({a: code, b: code});
  rt.reset();
  rt.dispatch('action', {key: 'a.run', ctx: {}});
  rt.dispatch('action', {key: 'a.run', ctx: {}});
  rt.dispatch('action', {key: 'b.run', ctx: {}});
  assert.deepStrictEqual(rt.take('toast').map((t) => t.text), ['1', '2', '1']);
});

test('a throwing plugin does not break others; error is reported', () => {
  const bad = 'plugin({name:"Bad", onCommit(){ throw new Error("boom"); }});';
  const good = 'plugin({name:"Good", onCommit(w){ return w.toUpperCase(); }});';
  const rt = makeRuntime({bad: bad, good: good});
  rt.reset();
  assert.strictEqual(rt.dispatch('onCommit', {word: 'hi', ctx: {}}).text, 'HI');
  assert.ok(rt.take('log').some((l) => l.level === 'error' && /boom/.test(l.msg)));
});

test('plugin that never calls plugin() or throws at load is flagged', () => {
  const rt = makeRuntime({none: 'var x = 1;', thrower: 'throw new Error("nope");'});
  const m = rt.meta().plugins;
  assert.ok(/never called/.test(m.find((p) => p.id === 'none').error));
  assert.ok(/nope/.test(m.find((p) => p.id === 'thrower').error));
});

test('action errors toast the message', () => {
  const rt = makeRuntime({e: 'plugin({name:"E", toolbar:[{run(){ undefinedFn(); }}]});'});
  rt.reset();
  rt.dispatch('action', {key: 'e.run', ctx: {}});
  assert.ok(/undefinedFn/.test(rt.take('toast')[0].text));
});

test('actions[] are layout-key only, toolbar[] go on the bar; ids default run, run2', () => {
  const rt = makeRuntime({m: 'plugin({name:"M", toolbar:[{icon:"A",run(){}},{icon:"B",run(){}}], actions:[{id:"x",icon:"C",run(){}}]});'});
  assert.deepStrictEqual(rt.meta().plugins[0].actions, [
    {key: 'm.run', icon: 'A', toolbar: true}, {key: 'm.run2', icon: 'B', toolbar: true}, {key: 'm.x', icon: 'C', toolbar: false},
  ]);
});

test('syntactically broken plugin source is rejected at evaluate time (what Kotlin catches)', () => {
  const sb = {__host: {call: () => ''}};
  vm.createContext(sb);
  vm.runInContext(prelude, sb);
  assert.throws(() => vm.runInContext(wrap('broken', 'plugin({ name: '), sb));
});


// ---------------------------------------------------------------- canvas + cross-plugin
test('keys.canvas: open, batched ops, touch/resize/button routing, close', () => {
  const rt = makeRuntime({cv: `plugin({name:"C", toolbar:[{run(){
    globalThis.log = [];
    const c = keys.canvas({title:"T", onResize(w,h){ log.push('r'+w+'x'+h); c.rect(0,0,w,h,{fill:'#fff'}); },
      onTouch(e){ log.push(e.type+e.x); c.line(0,0,e.x,e.y); c.dot(e.x,e.y); },
      onClose(){ log.push('closed'); },
      buttons:[{label:"B", onTap(){ log.push('btn'); }}]});
    globalThis.c = c;
  }}]});`});
  rt.reset();
  rt.dispatch('action', {key: 'cv.run', ctx: {}});
  const open = rt.take('canvas');
  assert.strictEqual(open[0].op, 'open');
  assert.strictEqual(open[1].op, 'ops');                       // buttons, flushed when the hook returned
  assert.strictEqual(open[1].ops[0].o, 'ui');
  const cid = open[0].cid;
  rt.dispatch('canvas', {plugin: 'cv', cid, size: {w: 300, h: 150}});
  let ops = rt.take('canvas')[0].ops;
  assert.deepStrictEqual([ops[0].o, ops[0].w, ops[0].f], ['rect', 300, '#fff']);
  rt.dispatch('canvas', {plugin: 'cv', cid, pts: [{t: 'down', id: 0, x: 5, y: 6, p: 1}, {t: 'move', id: 0, x: 7, y: 8, p: 1}]});
  ops = rt.take('canvas')[0].ops;                               // 2 events -> ONE batch of 4 ops
  assert.deepStrictEqual(ops.map((o) => o.o), ['line', 'path', 'line', 'path']);
  assert.deepStrictEqual(ops[1].pts, [5, 6]);
  rt.dispatch('canvas', {plugin: 'cv', cid, btn: 0});
  rt.dispatch('canvas', {plugin: 'cv', cid: cid + 99, btn: 0});  // stale canvas id is ignored
  rt.dispatch('canvas', {plugin: 'cv', cid, closed: true});
  rt.dispatch('canvas', {plugin: 'cv', cid, pts: [{t: 'down', id: 0, x: 1, y: 1, p: 1}]});
  assert.strictEqual(rt.take('canvas').length, 0);              // nothing after close
  assert.strictEqual(vm.runInContext('log.join()', rt.sandbox), ['r300x150', 'down5', 'move7', 'btn', 'closed'].join());
});

test('keys.canvas: opening a second canvas closes the first; save/restore/text/erase ops', () => {
  const rt = makeRuntime({cv: `plugin({name:"C", toolbar:[{run(){
    const c = keys.canvas({onClose(){ keys.toast('old closed'); }});
    c.save(); c.text("hi", 1, 2, {size: 20, align: 'center'}); c.line(0,0,1,1,{erase:true,width:9}); c.restore();
    c.path([[0,0],[1,1],{x:2,y:2}], {smooth:true, closed:true, fill:'red'});
  }}]});`});
  rt.reset();
  rt.dispatch('action', {key: 'cv.run', ctx: {}});
  const ops = rt.take('canvas').find((c) => c.op === 'ops').ops;
  assert.deepStrictEqual(ops.map((o) => o.o), ['save', 'text', 'line', 'restore', 'path']);
  assert.strictEqual(ops[1].size, 20);
  assert.strictEqual(ops[2].erase, 1);
  assert.deepStrictEqual(ops[4].pts, [0, 0, 1, 1, 2, 2]);
  assert.strictEqual(ops[4].f, 'red');
  assert.strictEqual(ops[4].s, undefined);                      // fill only -> no outline
  rt.dispatch('action', {key: 'cv.run', ctx: {}});              // reopen
  assert.ok(rt.take('toast').some((t) => t.text === 'old closed'));
});

test('draw plugin: pen draws with undo snapshot, eraser erases, send inserts the image', () => {
  const rt = makeRuntime({draw: load('draw')});
  rt.reset();
  rt.dispatch('action', {key: 'draw.open', ctx: {}});
  const cid = rt.take('canvas')[0].cid;
  rt.dispatch('canvas', {plugin: 'draw', cid, pts: [{t: 'down', id: 0, x: 5, y: 6, p: 1}, {t: 'move', id: 0, x: 9, y: 9, p: 1}, {t: 'up', id: 0, x: 9, y: 9, p: 1}]});
  let ops = rt.take('canvas')[0].ops;
  assert.deepStrictEqual(ops.map((o) => o.o), ['save', 'path', 'line']);
  assert.strictEqual(ops[2].s, '#000000');
  rt.dispatch('canvas', {plugin: 'draw', cid, btn: 2});          // 🧽 (tool index 2)
  rt.reset();
  rt.dispatch('canvas', {plugin: 'draw', cid, pts: [{t: 'down', id: 0, x: 1, y: 1, p: 1}, {t: 'move', id: 0, x: 2, y: 2, p: 1}, {t: 'up', id: 0, x: 2, y: 2, p: 1}]});
  ops = rt.take('canvas')[0].ops;
  assert.ok(ops.filter((o) => o.o === 'line').every((o) => o.erase === 1 && o.lw === 12));
  rt.dispatch('canvas', {plugin: 'draw', cid, btn: 3});          // 🪣 fill tool
  rt.reset();
  rt.dispatch('canvas', {plugin: 'draw', cid, pts: [{t: 'down', id: 0, x: 10, y: 20, p: 1}, {t: 'up', id: 0, x: 10, y: 20, p: 1}]});
  ops = rt.take('canvas')[0].ops;
  assert.deepStrictEqual(ops.map((o) => o.o), ['save', 'flood']);
  assert.deepStrictEqual([ops[1].x, ops[1].y], [10, 20]);
  rt.dispatch('canvas', {plugin: 'draw', cid, btn: 5});          // ▭ rect: live preview = restore, save, rect
  rt.reset();
  rt.dispatch('canvas', {plugin: 'draw', cid, pts: [{t: 'down', id: 0, x: 1, y: 1, p: 1}, {t: 'move', id: 0, x: 9, y: 7, p: 1}, {t: 'up', id: 0, x: 9, y: 7, p: 1}]});
  ops = rt.take('canvas')[0].ops;
  assert.deepStrictEqual(ops.map((o) => o.o), ['save', 'restore', 'save', 'rect']);
  assert.deepStrictEqual([ops[3].w, ops[3].h], [8, 6]);
  rt.dispatch('canvas', {plugin: 'draw', cid, btn: 0});          // back to pen
  rt.reset();
  rt.dispatch('canvas', {plugin: 'draw', cid, btn: 7 + 12 + 4 + 3});   // 📤 (last button)
  assert.strictEqual(rt.take('canvas')[0].ops[0].o, 'insert');
  assert.strictEqual(rt.meta().plugins[0].error, undefined);
});

test('keys.use / api / onLoad / on / emit / run', () => {
  const rt = makeRuntime({
    a: 'plugin({name:"A", api:{twice(x){return x*2;}}, onLoad(){ keys.on("ping", (d) => keys.toast("got " + d)); }});',
    b: 'plugin({name:"B", onLoad(){ keys.toast("api " + keys.use("a").twice(21)); }, toolbar:[{id:"go", run(){ keys.emit("ping", 7); keys.run("b.other"); }}], actions:[{id:"other", run(){ keys.toast("other"); }}]});',
  });
  rt.meta();                                                    // the host calls this once after loading -> onLoad
  assert.ok(rt.take('toast').some((t) => t.text === 'api 42'));
  rt.dispatch('action', {key: 'b.go', ctx: {}});
  assert.deepStrictEqual(rt.take('toast').map((t) => t.text), ['got 7', 'other']);
  assert.strictEqual(vm.runInContext('1', rt.sandbox), 1);
});

test('canvas: gradients, transforms, clip, arc/bezier/polygon/star, image, snapshot, measureText, animate', () => {
  const rt = makeRuntime({g: `plugin({name:"G", toolbar:[{run(){
    const c = keys.canvas({height: 150, toolbar: false});
    globalThis.c = c; globalThis.snap = null;
    c.push(); c.translate(10, 20); c.rotate(45); c.scale(2); c.clipRect(0,0,5,5); c.clipCircle(1,1,2);
    c.rect(0,0,10,10,{fill: c.linearGradient(0,0,10,0,['#f00','#00f']), alpha:.5, blend:'multiply', dash:[4,2], shadow:{blur:3}});
    c.circle(1,1,1,{fill: c.radialGradient(1,1,5,[[0,'#fff'],[1,'#000']])});
    c.pop(); c.resetTransform(); c.clipReset();
    c.arc(0,0,10,10,0,90,{pie:true,fill:'red'}); c.bezier(0,0,1,1,2,2); c.bezier(0,0,1,1,2,2,3,3,{color:'#0f0'});
    c.polygon(0,0,5,6); c.star(0,0,5,2,5,{fill:'#ff0'}); c.flood(1,2,'#0f0'); c.image('data:image/png;base64,AAAA',0,0,10,10,{alpha:.5});
    c.text('hi',0,0,{font:'mono',italic:true,bold:true,stroke:'#fff',fill:c.sweepGradient(0,0,['#f00','#0f0'])});
    c.clearRect(0,0,1,1); c.fillAll('#123'); c.snapshot((d) => { globalThis.snap = d; }, {max: 64});
    globalThis.w = c.measureText('abc', 10);
    globalThis.n = 0; globalThis.stop = c.animate((dt) => { n++; c.dot(n, n); }, 20);
  }}]});`});
  rt.reset();
  rt.dispatch('action', {key: 'g.run', ctx: {}});
  const calls = rt.take('canvas');
  assert.strictEqual(calls[0].spec.height, 150);
  const ops = calls.find((x) => x.op === 'ops').ops;
  const names = ops.map((o) => o.o + (o.k ? ':' + o.k : ''));
  assert.deepStrictEqual(names.slice(0, 8), ['bar', 'tf:push', 'tf:translate', 'tf:rotate', 'tf:scale', 'tf:clipRect', 'tf:clipCircle', 'rect']);
  const rect = ops.find((o) => o.o === 'rect');
  assert.deepStrictEqual(rect.f.stops, [[0, '#f00'], [1, '#00f']]);
  assert.strictEqual(rect.blend, 'multiply');
  assert.deepStrictEqual(rect.dash, [4, 2]);
  assert.strictEqual(ops.find((o) => o.o === 'arc').center, 1);
  assert.strictEqual(ops.filter((o) => o.o === 'bezier').map((o) => o.pts.length).join(), '6,8');
  assert.strictEqual(ops.filter((o) => o.o === 'path' && o.closed).map((o) => o.pts.length).join(), '12,20');   // hexagon, 5-point star
  assert.strictEqual(ops.find((o) => o.o === 'flood').tol, 24);
  const tx = ops.find((o) => o.o === 'text');
  assert.deepStrictEqual([tx.font, tx.italic, tx.bold, tx.s, tx.c.type], ['mono', 1, 1, '#fff', 'sweep']);
  assert.strictEqual(ops.find((o) => o.o === 'rect' && o.erase).erase, 1);
  const snap = ops.find((o) => o.o === 'snap');
  rt.dispatch('canvas', {plugin: 'g', cid: calls[0].cid, snap: snap.id, data: 'data:image/png;base64,XYZ'});
  assert.strictEqual(vm.runInContext('snap', rt.sandbox), 'data:image/png;base64,XYZ');
  // animate = setInterval
  const iv = rt.pendingTimers.find((t) => t.repeat);
  assert.strictEqual(iv.ms, 50);
  rt.reset();
  rt.dispatch('__timer', String(rt.pendingTimers.indexOf(iv) + 1));
  assert.strictEqual(rt.take('canvas')[0].ops[0].o, 'path');
});

test('new keys api: sendKey mods, replaceAll, suggest, theme, vibrate, openUrl, storage keys/clear, selection hooks', () => {
  const rt = makeRuntime({k: `plugin({name:"K",
    onSelection(ctx, s, e){ keys.toast('sel ' + s + '-' + e); },
    onFinishInput(){ keys.toast('done'); },
    toolbar:[{run(){
      keys.sendKey('c', {ctrl: true}); keys.replaceAll('x'); keys.suggest(['a','b']); keys.vibrate(30); keys.openUrl('https://e.x');
      keys.storage.set('a', 1); keys.storage.set('b', 2); keys.toast(JSON.stringify(keys.storage.keys())); keys.storage.clear();
    }}]});`});
  assert.deepStrictEqual(rt.meta().plugins[0].hooks, ['onSelection', 'onFinishInput']);
  rt.reset();
  rt.dispatch('action', {key: 'k.run', ctx: {}});
  assert.deepStrictEqual(rt.take('key')[0], {name: 'c', mods: {ctrl: true}});
  rt.reset();
  rt.dispatch('action', {key: 'k.run', ctx: {}});
  const fns = rt.calls.map((c) => c.fn);
  assert.ok(['edit', 'commit', 'suggest', 'vibrate', 'openUrl', 'storeClear', 'storeKeys'].every((f) => fns.includes(f)));
  assert.strictEqual(rt.calls.find((c) => c.fn === 'suggest').o.seq, -1);
  rt.reset();
  rt.dispatch('onSelection', {start: 1, end: 3, ctx: {}});
  rt.dispatch('onFinishInput', {ctx: {}});
  assert.deepStrictEqual(rt.take('toast').map((t) => t.text), ['sel 1-3', 'done']);
});

console.log('\n' + passed + ' tests passed');
