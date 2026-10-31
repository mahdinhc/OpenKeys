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

const load = (id) => fs.readFileSync(path.join(pluginDir, id + '.js'), 'utf8');
let passed = 0;
const test = (name, fn) => { fn(); passed++; console.log('  ok  ' + name); };

// ---------------------------------------------------------------- every sample loads cleanly
const ids = fs.readdirSync(pluginDir).filter((f) => f.endsWith('.js')).map((f) => f.slice(0, -3));
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
  rt.dispatch('canvas', {plugin: 'draw', cid, btn: 1});          // 🧽
  rt.reset();
  rt.dispatch('canvas', {plugin: 'draw', cid, pts: [{t: 'down', id: 0, x: 1, y: 1, p: 1}, {t: 'move', id: 0, x: 2, y: 2, p: 1}, {t: 'up', id: 0, x: 2, y: 2, p: 1}]});
  ops = rt.take('canvas')[0].ops;
  assert.ok(ops.filter((o) => o.o === 'line').every((o) => o.erase === 1 && o.lw === 12));
  rt.reset();
  rt.dispatch('canvas', {plugin: 'draw', cid, btn: 16});         // 📤 (last button)
  assert.strictEqual(rt.take('canvas')[0].ops[0].o, 'insert');
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

console.log('\n' + passed + ' tests passed');
