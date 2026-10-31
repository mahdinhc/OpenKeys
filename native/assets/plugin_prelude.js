// OpenKeys plugin runtime. Runs inside QuickJS in the keyboard process.
// Kotlin provides  __host.call(fn, json) -> string   and calls back into  __bridge.dispatch(hook, json) -> string.
// Everything crossing the boundary is a JSON string, which keeps the native binding trivial.
(function (g) {
  'use strict';
  const H = g.__host;
  const plugins = {};          // id -> record
  const h = {};                // hook name -> handler(args)
  g.__defs = {};               // id -> wrapper function made from the plugin's source
  let cur = {};                // context of the hook/action currently running (before/after/selected/...)

  // ---------------------------------------------------------------- helpers
  const host = (fn, obj) => H.call(fn, obj === undefined ? '' : JSON.stringify(obj));
  const str = (v) => {
    if (typeof v === 'string') return v;
    if (v && v.message !== undefined) return v.stack ? String(v.stack) : String(v.message);
    try { const s = JSON.stringify(v); return s === undefined ? String(v) : s; } catch (e) { return String(v); }
  };
  const log = (id, level, msg) => { try { host('log', {plugin: id, level: level, msg: msg}); } catch (e) { /* ignore */ } };
  const fail = (rec, where, e) => log(rec.id, 'error', where + ': ' + str(e));
  const setCtx = (c) => { cur = c || {}; };
  const active = () => Object.keys(plugins).map((k) => plugins[k]).filter((r) => r.def && !r.error);

  // ---------------------------------------------------------------- timers
  let timerSeq = 0;
  const timers = {};
  const makeTimers = (rec) => {
    const set = (fn, ms, rep) => {
      if (typeof fn !== 'function') return 0;
      const id = ++timerSeq;
      timers[id] = {fn: fn, rec: rec, rep: rep};
      host('timer', {id: id, ms: Math.max(0, +ms || 0), repeat: rep});
      return id;
    };
    const clear = (id) => { if (timers[id]) { delete timers[id]; host('clearTimer', {id: id}); } };
    return {
      setTimeout: (fn, ms) => set(fn, ms, false),
      setInterval: (fn, ms) => set(fn, ms, true),
      clearTimeout: clear,
      clearInterval: clear,
    };
  };

  // ---------------------------------------------------------------- http
  let fetchSeq = 0;
  const fetches = {};
  const makeHttp = (rec) => (url, opts, cb) => {
    opts = opts || {};
    let headers = opts.headers || {};
    let body = opts.body;
    if (body !== undefined && body !== null && typeof body === 'object') {
      body = JSON.stringify(body);
      if (!headers['Content-Type']) headers = Object.assign({'Content-Type': 'application/json'}, headers);
    }
    const id = ++fetchSeq;
    fetches[id] = {cb: cb, rec: rec};
    host('fetch', {
      id: id, url: String(url), method: opts.method || 'GET', headers: headers,
      body: body === undefined || body === null ? null : String(body), timeout: opts.timeout || 10000,
    });
  };
  // Promise flavour. Works when the QuickJS binding runs pending jobs; the callback flavour (keys.http) always works.
  const makeFetch = (http) => (url, opts) => new Promise((resolve, reject) => {
    http(url, opts, (err, r) => {
      if (err) return reject(err);
      resolve({
        ok: r.ok, status: r.status,
        text: () => Promise.resolve(r.body),
        json: () => new Promise((res) => res(JSON.parse(r.body))),
      });
    });
  });

  // ---------------------------------------------------------------- panels
  let panelCbs = [];
  let panelOwner = null;
  const openPanel = (rec, spec) => {
    spec = spec || {};
    panelCbs = [];
    panelOwner = rec.id;
    const items = (spec.items || []).map((it, i) => {
      if (typeof it === 'string') return {label: it, text: it};
      const o = {label: String(it.label != null ? it.label : (it.text != null ? it.text : ''))};
      if (typeof it.onTap === 'function') panelCbs[i] = it.onTap;
      else if (it.text != null) o.text = String(it.text);
      return o;
    });
    host('panel', {plugin: rec.id, spec: {
      title: spec.title || rec.name, columns: spec.columns || 3, keepOpen: !!spec.keepOpen, items: items,
    }});
  };

  // ---------------------------------------------------------------- per-plugin environment
  const makeEnv = (rec) => {
    const id = rec.id;
    const t = makeTimers(rec);
    const http = makeHttp(rec);
    const fmt = (level) => (...a) => log(id, level, a.map(str).join(' '));
    const console = {log: fmt('log'), info: fmt('log'), debug: fmt('log'), warn: fmt('warn'), error: fmt('error')};
    const edit = (name) => () => host('edit', {name: name});
    const keys = {
      // --- writing text (async, executed in order on the UI thread)
      commit: (s, back) => host('commit', {text: String(s), back: back | 0}),
      deleteBefore: (n) => host('delete', {before: n | 0, after: 0}),
      deleteAfter: (n) => host('delete', {before: 0, after: n | 0}),
      replaceBefore: (n, s) => { host('delete', {before: n | 0, after: 0}); host('commit', {text: String(s), back: 0}); },
      sendKey: (name) => host('key', {name: String(name)}),
      moveCursor: (n) => host('move', {n: n | 0}),
      selectAll: edit('selectAll'), copy: edit('copy'), cut: edit('cut'), paste: edit('paste'),
      // --- reading text (snapshot taken when the hook/action started)
      before: (n) => { const b = cur.before || ''; return n === undefined ? b : b.slice(Math.max(0, b.length - n)); },
      after: (n) => { const a = cur.after || ''; return n === undefined ? a : a.slice(0, n); },
      selected: () => cur.selected || '',
      word: () => cur.word || '',
      ctx: () => cur,
      // --- misc
      toast: (s) => host('toast', {text: String(s)}),
      haptic: () => host('haptic'),
      switchLayout: (lid) => host('layout', {id: String(lid)}),
      panel: (spec) => openPanel(rec, spec),
      closePanel: () => host('panel', {plugin: id, close: true}),
      clipboard: {
        get: () => H.call('clipGet', ''),
        set: (s) => host('clipSet', {text: String(s)}),
      },
      storage: {
        get: (k, d) => {
          const v = H.call('storeGet', JSON.stringify({plugin: id, key: String(k)}));
          if (!v) return d === undefined ? null : d;
          try { return JSON.parse(v); } catch (e) { return d === undefined ? null : d; }
        },
        set: (k, v) => host('storeSet', {plugin: id, key: String(k), value: v === undefined ? 'null' : JSON.stringify(v)}),
        remove: (k) => host('storeDel', {plugin: id, key: String(k)}),
      },
      settings: rec.settings,
      http: http,
      log: console.log,
    };
    return {keys: keys, console: console, fetch: makeFetch(http), t: t};
  };

  // ---------------------------------------------------------------- loading
  g.__run = function (id, settingsJson) {
    const rec = {id: id, name: id, def: null, error: null, settings: {}, actions: {}, hooks: []};
    plugins[id] = rec;
    try { Object.assign(rec.settings, JSON.parse(settingsJson || '{}') || {}); } catch (e) { /* ignore */ }
    const env = makeEnv(rec);

    const addAction = (it, inBar) => {
      if (!it || typeof it.run !== 'function') return;
      const n = Object.keys(rec.actions).length;
      const aid = String(it.id || (n === 0 ? 'run' : 'run' + (n + 1)));
      rec.actions[aid] = {icon: String(it.icon || '\u2022'), run: it.run, toolbar: inBar};
    };
    const register = (def) => {
      rec.def = def || {};
      rec.name = String(rec.def.name || id);
      const sch = rec.def.settings || {};
      Object.keys(sch).forEach((k) => {
        if (rec.settings[k] === undefined && sch[k] && sch[k].default !== undefined) rec.settings[k] = sch[k].default;
      });
      (rec.def.toolbar || []).forEach((it) => addAction(it, true));
      (rec.def.actions || []).forEach((it) => addAction(it, false));
      ['onCommit', 'onText', 'suggest', 'onStartInput'].forEach((n) => { if (typeof rec.def[n] === 'function') rec.hooks.push(n); });
    };

    try {
      g.__defs[id](register, env.keys, env.console, env.fetch,
        env.t.setTimeout, env.t.setInterval, env.t.clearTimeout, env.t.clearInterval);
      if (!rec.def) throw new Error('plugin({...}) was never called');
    } catch (e) {
      rec.error = e && e.message ? String(e.message) : String(e);
      log(id, 'error', str(e));
    }
  };

  // ---------------------------------------------------------------- hooks (called from Kotlin)
  h.__meta = () => ({
    plugins: Object.keys(plugins).map((id) => {
      const r = plugins[id];
      const d = r.def || {};
      return {
        id: id, name: r.name, description: d.description, version: d.version, error: r.error || undefined,
        settings: d.settings, hooks: r.hooks,
        actions: Object.keys(r.actions).map((a) => ({key: id + '.' + a, icon: r.actions[a].icon, toolbar: r.actions[a].toolbar})),
      };
    }),
  });

  // sync: runs before a typed word is committed. May return a replacement string.
  h.onCommit = (a) => {
    let w = a.word;
    let changed = false;
    active().forEach((r) => {
      if (typeof r.def.onCommit !== 'function') return;
      setCtx(a.ctx);
      try {
        const x = r.def.onCommit(w, a.ctx);
        if (typeof x === 'string' && x !== w) { w = x; changed = true; }
      } catch (e) { fail(r, 'onCommit', e); }
    });
    return changed ? {text: w} : null;
  };

  // async: runs just before a character is inserted
  h.onText = (a) => {
    active().forEach((r) => {
      if (typeof r.def.onText !== 'function') return;
      setCtx(a.ctx);
      try { r.def.onText(a.text, a.ctx); } catch (e) { fail(r, 'onText', e); }
    });
  };

  // async: return an array, or call done(array) later (also accepts a Promise)
  h.suggest = (a) => {
    active().forEach((r) => {
      if (typeof r.def.suggest !== 'function') return;
      setCtx(a.ctx);
      const done = (list) => {
        if (!Array.isArray(list) || !list.length) return;
        host('suggest', {seq: a.seq, plugin: r.id, list: list.map(String).slice(0, 5)});
      };
      try {
        const x = r.def.suggest(a.ctx, done);
        if (x && typeof x.then === 'function') x.then(done, (e) => fail(r, 'suggest', e));
        else if (Array.isArray(x)) done(x);
      } catch (e) { fail(r, 'suggest', e); }
    });
  };

  h.onStartInput = (a) => {
    active().forEach((r) => {
      if (typeof r.def.onStartInput !== 'function') return;
      setCtx(a.ctx);
      try { r.def.onStartInput(a.ctx); } catch (e) { fail(r, 'onStartInput', e); }
    });
  };

  // toolbar button / layout key pressed. key = "<pluginId>.<actionId>"
  h.action = (a) => {
    const dot = String(a.key).indexOf('.');
    const r = plugins[String(a.key).slice(0, dot)];
    const act = r && r.actions[String(a.key).slice(dot + 1)];
    if (!act || r.error) { host('toast', {text: 'Plugin action unavailable: ' + a.key}); return; }
    setCtx(a.ctx);
    try { act.run(a.ctx); } catch (e) {
      fail(r, a.key, e);
      host('toast', {text: r.name + ': ' + (e && e.message ? e.message : String(e))});
    }
  };

  h.panel = (a) => {
    const cb = a.plugin === panelOwner ? panelCbs[a.index] : null;
    if (typeof cb !== 'function') return;
    const r = plugins[a.plugin];
    setCtx(a.ctx);
    try { cb(a.ctx); } catch (e) {
      if (r) fail(r, 'panel', e);
      host('toast', {text: (r ? r.name : 'plugin') + ': ' + (e && e.message ? e.message : String(e))});
    }
  };

  h.__timer = (id) => {
    const tm = timers[id];
    if (!tm) return;
    if (!tm.rep) delete timers[id];
    setCtx({});
    try { tm.fn(); } catch (e) { fail(tm.rec, 'timer', e); }
  };

  h.__fetch = (a) => {
    const f = fetches[a.id];
    delete fetches[a.id];
    if (!f || typeof f.cb !== 'function') return;
    try {
      if (a.error) f.cb(new Error(a.error));
      else f.cb(null, {ok: a.ok, status: a.status, body: a.text || '', json: () => JSON.parse(a.text || 'null')});
    } catch (e) { fail(f.rec, 'http callback', e); }
  };

  g.__bridge = {
    dispatch: function (hook, json) {
      try {
        const fn = h[hook];
        if (!fn) return '';
        let arg = json ? JSON.parse(json) : {};
        if (hook === '__timer') arg = Number(json);
        const r = fn(arg);
        return r === undefined || r === null ? '' : JSON.stringify(r);
      } catch (e) {
        log('host', 'error', hook + ': ' + str(e));
        return '';
      }
    },
  };
})(globalThis);
