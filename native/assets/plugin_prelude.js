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


  // ---------------------------------------------------------------- canvas (keys.canvas)
  // Draw ops are queued and sent in one batch when the current hook/callback returns (or on c.flush()).
  let cvSeq = 0;
  let activeCv = null;
  const flushCv = (cv) => {
    if (!cv || cv.closed || !cv.q.length) return;
    const ops = cv.q; cv.q = [];
    host('canvas', {op: 'ops', plugin: cv.rec.id, cid: cv.cid, ops: ops});
  };
  const endCv = (cv, notify) => {
    if (!cv || cv.closed) return;
    cv.closed = true;
    if (activeCv === cv) activeCv = null;
    if (notify && typeof cv.spec.onClose === 'function') {
      try { cv.spec.onClose(); } catch (e) { fail(cv.rec, 'canvas onClose', e); }
    }
  };
  const flat = (pts) => {
    if (!pts || !pts.length) return [];
    if (typeof pts[0] === 'number') return pts.map(Number);
    const o = [];
    pts.forEach((p) => { if (Array.isArray(p)) o.push(+p[0], +p[1]); else o.push(+p.x, +p.y); });
    return o;
  };
  const val = (v) => (v !== null && typeof v === 'object') ? v : String(v);
  const norm = (st) => (st || []).map((x, i, arr) => Array.isArray(x) ? [+x[0], String(x[1])] : [arr.length > 1 ? i / (arr.length - 1) : 0, String(x)]);
  const openCanvas = (rec, spec, t) => {
    spec = spec || {};
    if (activeCv) endCv(activeCv, true);
    const cv = {cid: ++cvSeq, rec: rec, spec: spec, q: [], cbs: [], closed: false, w: 0, h: 0, snaps: {}, snapSeq: 0};
    activeCv = cv;
    const D = {color: '#000000', width: 2, size: 16};
    const push = (o) => { if (!cv.closed) cv.q.push(o); };
    const shape = (k, g, o) => {
      o = o || {};
      const op = Object.assign({o: k}, g);
      const stroke = o.stroke !== undefined ? o.stroke : o.color;
      if (o.fill !== undefined) op.f = val(o.fill);
      if (stroke !== undefined) op.s = val(stroke); else if (o.fill === undefined) op.s = D.color;
      op.lw = o.width !== undefined ? +o.width : D.width;
      if (o.erase) op.erase = 1;
      ['cap', 'join', 'alpha', 'blend', 'dash', 'shadow'].forEach((n) => { if (o[n] !== undefined) op[n] = o[n]; });
      if (o.radius) op.r = +o.radius;
      if (o.closed) op.closed = 1;
      if (o.smooth) op.smooth = 1;
      push(op);
    };
    const tf = (k, g) => push(Object.assign({o: 'tf', k: k}, g));
    const setButtons = (list) => {
      cv.cbs = [];
      push({o: 'ui', buttons: (list || []).map((b, i) => { cv.cbs[i] = b.onTap; return {label: String(b.label), active: !!b.active}; })});
    };
    const rad = (deg) => deg * Math.PI / 180;
    const api = {
      defaults: D,
      get width() { return cv.w; },
      get height() { return cv.h; },
      get closed() { return cv.closed; },
      // --- basics
      clear: () => push({o: 'clear'}),
      background: (c) => push({o: 'bg', c: String(c)}),
      line: (x1, y1, x2, y2, o) => shape('line', {x1: x1, y1: y1, x2: x2, y2: y2}, o),
      rect: (x, y, w, h, o) => shape('rect', {x: x, y: y, w: w, h: h}, o),
      circle: (x, y, r, o) => shape('circle', {x: x, y: y, r: r}, o),
      oval: (x, y, w, h, o) => shape('oval', {x: x, y: y, w: w, h: h}, o),
      arc: (x, y, w, h, start, sweep, o) => shape('arc', {x: x, y: y, w: w, h: h, a0: start, sweep: sweep, center: o && o.pie ? 1 : 0}, o),
      path: (pts, o) => shape('path', {pts: flat(pts)}, o),
      dot: (x, y, o) => shape('path', {pts: [x, y]}, o),
      // quadratic (6 numbers) or cubic (8 numbers) bezier curve: bezier(x0,y0,cx,cy,x1,y1) / bezier(x0,y0,c1x,c1y,c2x,c2y,x1,y1)
      bezier: (...a) => { const o = (a.length === 7 || a.length === 9) ? a.pop() : undefined; shape('bezier', {pts: a.map(Number)}, o); },
      polygon: (cx, cy, r, sides, o) => {
        const p = [], rot = rad(((o && o.rotation) || 0) - 90);
        for (let i = 0; i < sides; i++) p.push(cx + r * Math.cos(rot + 2 * Math.PI * i / sides), cy + r * Math.sin(rot + 2 * Math.PI * i / sides));
        shape('path', {pts: p}, Object.assign({closed: true}, o));
      },
      star: (cx, cy, r1, r2, n, o) => {
        const p = [], rot = rad(((o && o.rotation) || 0) - 90);
        for (let i = 0; i < n * 2; i++) { const r = i % 2 ? r2 : r1, a = rot + Math.PI * i / n; p.push(cx + r * Math.cos(a), cy + r * Math.sin(a)); }
        shape('path', {pts: p}, Object.assign({closed: true}, o));
      },
      clearRect: (x, y, w, h) => shape('rect', {x: x, y: y, w: w, h: h}, {fill: '#000', erase: true}),
      fillAll: (c, o) => shape('rect', {x: -5000, y: -5000, w: 10000, h: 10000}, Object.assign({fill: c}, o)),
      grid: (step, o) => {
        o = o || {};
        const W = cv.w || 600, H = cv.h || 300;
        for (let x = 0; x <= W; x += step) shape('line', {x1: x, y1: 0, x2: x, y2: H}, Object.assign({width: 0.5, color: '#00000030'}, o));
        for (let y = 0; y <= H; y += step) shape('line', {x1: 0, y1: y, x2: W, y2: y}, Object.assign({width: 0.5, color: '#00000030'}, o));
      },
      // --- text.   o: size, color|fill, stroke, width, align, bold, italic, font: "sans"|"serif"|"mono", shadow, alpha ...
      text: (str, x, y, o) => {
        o = o || {};
        const op = {o: 'text', t: String(str), x: x, y: y, size: o.size || D.size, c: val(o.color || o.fill || D.color)};
        if (o.stroke !== undefined) { op.s = val(o.stroke); op.lw = o.width || 1; }
        ['align', 'font', 'alpha', 'blend', 'shadow'].forEach((n) => { if (o[n] !== undefined) op[n] = o[n]; });
        if (o.bold) op.bold = 1;
        if (o.italic) op.italic = 1;
        if (o.erase) op.erase = 1;
        push(op);
      },
      measureText: (str, size, o) => {
        o = o || {};
        return parseFloat(H.call('measure', JSON.stringify({text: String(str), size: size || D.size, bold: !!o.bold, italic: !!o.italic, font: o.font}))) || 0;
      },
      // --- gradients: use as fill / stroke / text color
      linearGradient: (x1, y1, x2, y2, stops) => ({type: 'linear', x1: x1, y1: y1, x2: x2, y2: y2, stops: norm(stops)}),
      radialGradient: (x, y, r, stops) => ({type: 'radial', x: x, y: y, r: r, stops: norm(stops)}),
      sweepGradient: (x, y, stops) => ({type: 'sweep', x: x, y: y, stops: norm(stops)}),
      // --- transforms + clipping (affect everything drawn afterwards)
      translate: (x, y) => tf('translate', {x: x, y: y}),
      rotate: (deg, x, y) => tf('rotate', x === undefined ? {a: deg} : {a: deg, x: x, y: y}),
      scale: (sx, sy, px, py) => tf('scale', px === undefined ? {x: sx, y: sy === undefined ? sx : sy} : {x: sx, y: sy, px: px, py: py}),
      resetTransform: () => tf('reset'),
      push: () => tf('push'),      // save transform + clip
      pop: () => tf('pop'),        // restore them
      clipRect: (x, y, w, h) => tf('clipRect', {x: x, y: y, w: w, h: h}),
      clipCircle: (x, y, r) => tf('clipCircle', {x: x, y: y, r: r}),
      clipReset: () => tf('clipReset'),
      // --- pixels
      flood: (x, y, color, tolerance) => push({o: 'flood', x: x, y: y, c: String(color), tol: tolerance === undefined ? 24 : tolerance}),   // bucket fill
      image: (src, x, y, w, h, o) => push({o: 'image', src: String(src), x: x, y: y, w: w, h: h, alpha: o && o.alpha}),   // data: URL, base64 or http(s) URL
      save: () => push({o: 'save'}),        // snapshot the bitmap (max 30) ...
      restore: () => push({o: 'restore'}),  // ... and go back to the latest one (undo)
      snapshot: (cb, o) => { const id = ++cv.snapSeq; cv.snaps[id] = cb; push({o: 'snap', id: id, max: (o && o.max) || 0, transparent: !!(o && o.transparent)}); },  // cb(dataUrl)
      insertImage: (opt) => push({o: 'insert', transparent: !!(opt && opt.transparent)}),
      // --- UI / lifecycle
      title: (str) => push({o: 'title', t: String(str)}),
      buttons: setButtons,
      toolbar: (show) => push({o: 'bar', show: show !== false}),
      animate: (fn, fps) => {
        let last = Date.now();
        const id = t.setInterval(() => {
          if (cv.closed) { t.clearInterval(id); return; }
          const n = Date.now(), dt = n - last; last = n;
          fn(dt, api);
        }, Math.max(10, Math.round(1000 / (fps || 30))));
        return () => t.clearInterval(id);
      },
      flush: () => flushCv(cv),
      close: () => { if (activeCv === cv) { host('canvas', {op: 'close', plugin: rec.id, cid: cv.cid}); endCv(cv, false); } },
    };
    host('canvas', {op: 'open', plugin: rec.id, cid: cv.cid, spec: {title: spec.title || '', bg: spec.background || spec.bg || '#ffffff', height: spec.height || 100}});
    if (spec.buttons) setButtons(spec.buttons);
    if (spec.toolbar === false) api.toolbar(false);
    return api;
  };

  // ---------------------------------------------------------------- plugin-to-plugin: events + api
  const bus = {};
  const busOn = (rec) => (ev, fn) => { if (typeof fn === 'function') (bus[ev] = bus[ev] || []).push({fn: fn, rec: rec}); };
  const busOff = (rec) => (ev, fn) => { bus[ev] = (bus[ev] || []).filter((l) => !(l.rec === rec && (fn === undefined || l.fn === fn))); };
  const busEmit = (ev, data) => { (bus[ev] || []).slice().forEach((l) => { try { l.fn(data); } catch (e) { fail(l.rec, 'event ' + ev, e); } }); };
  const useApi = (pid) => { const r = plugins[pid]; return r && r.def && !r.error && r.def.api ? r.def.api : null; };
  const runAction = (key, c) => {
    const k = String(key), dot = k.indexOf('.');
    const r = plugins[k.slice(0, dot)];
    const act = r && !r.error && r.actions[k.slice(dot + 1)];
    if (!act) throw new Error('no such action: ' + k);
    act.run(c || cur);
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
      sendKey: (name, mods) => host('key', {name: String(name), mods: mods || null}),   // mods: {ctrl, alt, shift, meta}
      replaceAll: (s) => { host('edit', {name: 'selectAll'}); host('commit', {text: String(s), back: 0}); },
      suggest: (list) => host('suggest', {seq: -1, plugin: id, list: (Array.isArray(list) ? list : []).map(String).slice(0, 5)}),
      theme: () => { try { return JSON.parse(H.call('theme', '')); } catch (e) { return {}; } },
      vibrate: (ms) => host('vibrate', {ms: ms === undefined ? 20 : ms | 0}),
      openUrl: (url) => host('openUrl', {url: String(url)}),
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
      canvas: (spec) => openCanvas(rec, spec, t),
      on: busOn(rec), off: busOff(rec), emit: busEmit, use: useApi, run: runAction,
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
        keys: () => { try { return JSON.parse(H.call('storeKeys', JSON.stringify({plugin: id}))); } catch (e) { return []; } },
        clear: () => host('storeClear', {plugin: id}),
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
      ['onCommit', 'onText', 'suggest', 'onStartInput', 'onSelection', 'onFinishInput'].forEach((n) => { if (typeof rec.def[n] === 'function') rec.hooks.push(n); });
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
  h.__meta = () => {
    // onLoad runs once per plugin after ALL plugins are loaded (safe place to call keys.use(other)).
    Object.keys(plugins).forEach((id) => {
      const r = plugins[id];
      if (r.def && !r.error && !r.loaded && typeof r.def.onLoad === 'function') {
        r.loaded = true;
        setCtx({});
        try { r.def.onLoad(); } catch (e) { fail(r, 'onLoad', e); }
      }
    });
    return h.__metaData();
  };
  h.__metaData = () => ({
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

  // selection/cursor moved: a = {start, end, ctx}
  h.onSelection = (a) => {
    active().forEach((r) => {
      if (typeof r.def.onSelection !== 'function') return;
      setCtx(a.ctx);
      try { r.def.onSelection(a.ctx, a.start, a.end); } catch (e) { fail(r, 'onSelection', e); }
    });
  };

  h.onFinishInput = (a) => {
    active().forEach((r) => {
      if (typeof r.def.onFinishInput !== 'function') return;
      setCtx(a.ctx);
      try { r.def.onFinishInput(a.ctx); } catch (e) { fail(r, 'onFinishInput', e); }
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

  // canvas events from Kotlin: {plugin, cid, size?|pts?|btn?|closed?}
  h.canvas = (a) => {
    const cv = activeCv;
    if (!cv || cv.closed || cv.cid !== a.cid || cv.rec.id !== a.plugin) return;
    const spec = cv.spec;
    setCtx({});
    try {
      if (a.size) { cv.w = a.size.w; cv.h = a.size.h; if (typeof spec.onResize === 'function') spec.onResize(cv.w, cv.h); }
      if (a.pts && typeof spec.onTouch === 'function') {
        a.pts.forEach((p) => {
          try { spec.onTouch({type: p.t, id: p.id, x: p.x, y: p.y, pressure: p.p, stylus: !!p.s}); } catch (e) { fail(cv.rec, 'canvas onTouch', e); }
        });
      }
      if (a.snap !== undefined) { const f = cv.snaps[a.snap]; delete cv.snaps[a.snap]; if (typeof f === 'function') f(a.data); }
      if (a.btn !== undefined) { const f = cv.cbs[a.btn]; if (typeof f === 'function') f(); }
      if (a.closed) endCv(cv, true);
    } catch (e) { fail(cv.rec, 'canvas', e); }
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
      } finally {
        if (activeCv) flushCv(activeCv);
      }
    },
  };
})(globalThis);
