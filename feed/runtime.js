(function () {
  "use strict";
  function install(fixture, opts) {
    opts = Object.assign({ owner: true, db: true, user: true, sample: true, downloads: true, latency: 0, jitter: 0, fromCacheFirst: false,
      sampleReply: "Here is a short answer from the mock runtime. Nothing was sent anywhere." }, opts || {});
    const clone = v => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));
    const deepFreeze = o => { if (o && typeof o === "object" && !Object.isFrozen(o)) { Object.freeze(o); Object.values(o).forEach(deepFreeze); } return o; };
    let seed = 0x5eed1234;
    const prng = () => { seed |= 0; seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
    const ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    const newId = () => { let s = ""; for (let i = 0; i < 20; i++) s += ABC[Math.floor(prng() * ABC.length)]; return s; };
    const err = (code, message) => Object.assign(new Error(message), { code, message });

    /* ---------- the store ---------- */
    const cols = new Map(); // collection path -> Map(id -> {v: version, data: frozen body})
    const col = p => { if (!cols.has(p)) cols.set(p, new Map()); return cols.get(p); };
    let version = 0;
    const load = (p, list) => (list || []).forEach(d => { const { id, ...data } = d; col(p).set(id, { v: ++version, data: deepFreeze(clone(data)) }); });
    Object.keys((fixture && fixture.cols) || {}).forEach(p => load(p, fixture.cols[p]));
    const M = { writes: [], queries: [], samples: [], downloads: [], subs: 0, maxSubs: 0, errors: [], cols,
      dump: p => [...col(p)].map(([id, r]) => Object.assign({ id }, clone(r.data))) };
    let saveTimer = 0;
    function persist() { clearTimeout(saveTimer); saveTimer = setTimeout(flush, 250); }
    function flush() { clearTimeout(saveTimer); const out = { v: 1, savedAt: Date.now(), cols: {} };
      cols.forEach((m, p) => { out.cols[p] = [...m].map(([id, r]) => Object.assign({ id }, r.data)); });
      try { IWLStore.save(JSON.stringify(out)); } catch (e) { console.error("save failed", e); } }
    window.__iwlFlush = flush; window.addEventListener("pagehide", flush); document.addEventListener("visibilitychange", () => { if (document.hidden) flush(); });

    /* ---------- path grammar (db.d.ts) ---------- */
    const SEG = /^[A-Za-z0-9_\-.~:@+]+$/;
    function segs(path, wantEven) {
      if (typeof path !== "string" || !path) throw new TypeError("path must be a non-empty string");
      const s = path.split("/");
      if (s.length > 16 || path.length > 1000) throw new TypeError("path too long");
      s.forEach(x => { if (!SEG.test(x) || x === "." || x === "..") throw new TypeError(`bad path segment "${x}"`); });
      if ((s.length % 2 === 0) !== wantEven) throw new TypeError(`${wantEven ? "document" : "collection"} paths need an ${wantEven ? "even" : "odd"} number of segments (got ${s.length})`);
      return s;
    }
    function checkBody(data) {
      if (!data || typeof data !== "object" || Array.isArray(data)) throw err("invalid_argument", "a document body must be a plain object");
      const json = JSON.stringify(data);
      if (json.length > 256 * 1024) throw err("invalid_argument", "document over 256 KiB");
      const depth = (o, n) => { if (n > 32) throw err("invalid_argument", "document deeper than 32 levels"); if (o && typeof o === "object") Object.values(o).forEach(v => depth(v, n + 1)); };
      depth(data, 1);
      return JSON.parse(json); // drops undefined, like the real store's JSON bodies
    }

    /* ---------- queries ---------- */
    const OPS = ["==", "!=", "<", "<=", ">", ">=", "in", "not-in", "array-contains"];
    const typeRank = v => (v === null ? 0 : typeof v === "boolean" ? 1 : typeof v === "number" ? 2 : typeof v === "string" ? 3 : Array.isArray(v) ? 4 : 5);
    const cmp = (a, b) => { const ta = typeRank(a), tb = typeRank(b); if (ta !== tb) return ta - tb; if (ta >= 4) return JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0; return a < b ? -1 : a > b ? 1 : 0; };
    const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
    function matches(data, f) {
      const has = Object.prototype.hasOwnProperty.call(data, f.field), v = data[f.field];
      if (!has) return false; // a missing field never matches a filter
      switch (f.op) {
        case "==": return eq(v, f.value);
        case "!=": return !eq(v, f.value);
        case "<": return typeRank(v) === typeRank(f.value) && cmp(v, f.value) < 0;
        case "<=": return typeRank(v) === typeRank(f.value) && cmp(v, f.value) <= 0;
        case ">": return typeRank(v) === typeRank(f.value) && cmp(v, f.value) > 0;
        case ">=": return typeRank(v) === typeRank(f.value) && cmp(v, f.value) >= 0;
        case "in": return f.value.some(x => eq(v, x));
        case "not-in": return !f.value.some(x => eq(v, x));
        case "array-contains": return Array.isArray(v) && v.some(x => eq(x, f.value));
      }
      return false;
    }
    function run(q) {
      const c = col(q.path), rows = [];
      for (const [id, r] of c) if (q.filters.every(f => matches(r.data, f))) rows.push({ id, r });
      const o = q.order;
      rows.sort((x, y) => {
        if (o) {
          const hx = Object.prototype.hasOwnProperty.call(x.r.data, o.field), hy = Object.prototype.hasOwnProperty.call(y.r.data, o.field);
          if (hx !== hy) return hx ? -1 : 1; // missing sorts last, either direction
          if (hx) { const k = cmp(x.r.data[o.field], y.r.data[o.field]); if (k) return o.dir === "desc" ? -k : k; }
          return o.dir === "desc" ? (x.id < y.id ? 1 : -1) : (x.id < y.id ? -1 : 1);
        }
        return x.id < y.id ? -1 : 1;
      });
      return q.lim ? rows.slice(0, q.lim) : rows;
    }
    function validate(q) {
      if (q.filters.length > 10) throw err("invalid_argument", "too many filters (at most 10)");
      for (const f of q.filters) {
        if (!OPS.includes(f.op)) throw err("invalid_argument", `unknown operator ${f.op}`);
        if ((f.op === "in" || f.op === "not-in") && (!Array.isArray(f.value) || f.value.length > 30)) throw err("invalid_argument", "in / not-in take an array of at most 30");
      }
      if (q.orders > 1) throw err("invalid_argument", "at most one orderBy");
      if (q.lim != null && !(Number.isInteger(q.lim) && q.lim >= 1 && q.lim <= 1000)) throw err("invalid_argument", "limit must be 1-1000");
    }

    /* ---------- snapshots ---------- */
    const META = Object.freeze({ fromCache: false, hasPendingWrites: false }), META_CACHE = Object.freeze({ fromCache: true, hasPendingWrites: false });
    const snapCache = new Map(); // "path/id@v" -> frozen DocumentSnapshot, so an unchanged doc is the same object
    function docSnap(path, id, r, meta = META) {
      const k = path + "/" + id + "@" + (r ? r.v : "none") + (meta === META ? "" : "c");
      if (!r) { if (!snapCache.has(k)) snapCache.set(k, Object.freeze({ id, exists: false, data: () => undefined, metadata: meta })); return snapCache.get(k); }
      if (!snapCache.has(k)) snapCache.set(k, Object.freeze({ id, exists: true, data: () => r.data, metadata: meta }));
      return snapCache.get(k);
    }
    function querySnap(path, rows, prev, meta = META) {
      const docs = rows.map(({ id, r }) => docSnap(path, id, r, meta));
      const changes = [], before = prev || [], idxOld = new Map(before.map((d, i) => [d.id, i])), idxNew = new Map(docs.map((d, i) => [d.id, i]));
      before.forEach((d, i) => { if (!idxNew.has(d.id)) changes.push(Object.freeze({ type: "removed", doc: d, oldIndex: i, newIndex: -1 })); });
      docs.forEach((d, i) => { if (!idxOld.has(d.id)) changes.push(Object.freeze({ type: "added", doc: d, oldIndex: -1, newIndex: i })); else if (before[idxOld.get(d.id)] !== d || idxOld.get(d.id) !== i) changes.push(Object.freeze({ type: "modified", doc: d, oldIndex: idxOld.get(d.id), newIndex: i })); });
      return Object.freeze({ docs: Object.freeze(docs), size: docs.length, empty: !docs.length, docChanges: () => changes.slice(), metadata: meta });
    }
    const listeners = new Set();
    const later = fn => setTimeout(fn, opts.latency + (opts.jitter ? Math.floor(prng() * opts.jitter) : 0));
    function subscribe(make, next, error) {
      if (M.subs >= 64) { later(() => error ? error(err("resource_exhausted", "subscription cap (64 per view)")) : reportError(err("resource_exhausted", "subscription cap"))); return () => {}; }
      const L = { make, next, error, prev: null, dead: false, first: true };
      M.subs++; M.maxSubs = Math.max(M.maxSubs, M.subs);
      listeners.add(L);
      later(() => deliver(L));
      return () => { if (L.dead) return; L.dead = true; listeners.delete(L); M.subs--; };
    }
    function deliver(L) {
      if (L.dead) return;
      let s;
      try { s = L.make(L.prev); }
      catch (e) { L.dead = true; listeners.delete(L); M.subs--; M.errors.push(e.code || String(e)); if (L.error) L.error({ code: e.code || "invalid_argument", message: e.message }); else reportError(e); return; }
      if (L.first && opts.fromCacheFirst && s.docs) { L.first = false; const part = opts.fromCacheFirst === "partial" ? s.docs.slice(0, Math.floor(s.docs.length / 2)).map(d => ({ id: d.id, r: { v: "c", data: d.data() } })) : [];
        const c = querySnap("", part, null, META_CACHE); try { L.next(c); } catch (e) { reportError(e); } }
      L.first = false;
      if (L.prev && sameSnap(L.prev, s)) return;
      L.prev = s.docs ? s.docs : s;
      try { L.next(s); } catch (e) { reportError(e); }
    }
    const sameSnap = (prev, s) => (s.docs ? Array.isArray(prev) && prev.length === s.docs.length && prev.every((d, i) => d === s.docs[i]) : prev === s);
    function changed() { listeners.forEach(L => later(() => deliver(L))); }

    /* ---------- refs ---------- */
    function query(path, filters, order, orders, lim) {
      const q = { path, filters, order, orders, lim };
      const self = {
        where(field, op, value) { return query(path, filters.concat([{ field, op, value }]), order, orders, lim); },
        orderBy(field, dir = "asc") { return query(path, filters, { field, dir: dir === "desc" ? "desc" : "asc" }, orders + 1, lim); },
        limit(n) { return query(path, filters, order, orders, n); },
        get() { return new Promise((res, rej) => later(() => { try { validate(q); res(querySnap(path, run(q), null)); } catch (e) { rej({ code: e.code || "invalid_argument", message: e.message }); } })); },
        onSnapshot(next, error) { return subscribe(prev => { validate(q); return querySnap(path, run(q), prev); }, next, error); }
      };
      return self;
    }
    function write(op, path, id, data) {
      const c = col(path), had = c.get(id);
      if (op === "update" && !had) throw err("invalid_argument", "update needs an existing document");
      let body = null;
      if (op === "set") body = checkBody(data);
      if (op === "update") { body = merge(clone(had.data), checkBody(data)); }
      if (op === "delete") c.delete(id); else c.set(id, { v: ++version, data: deepFreeze(body) });
      persist();
      changed();
    }
    const merge = (a, b) => { for (const [k, v] of Object.entries(b)) { if (v && typeof v === "object" && !Array.isArray(v) && a[k] && typeof a[k] === "object" && !Array.isArray(a[k])) merge(a[k], v); else a[k] = v; } return a; };
    function docRef(path, id) {
      const self = {
        id, path: path + "/" + id,
        get() { return new Promise(res => later(() => res(docSnap(path, id, col(path).get(id))))); },
        set(data) { return new Promise((res, rej) => later(() => { try { write("set", path, id, data); res(); } catch (e) { rej({ code: e.code, message: e.message }); } })); },
        update(data) { return new Promise((res, rej) => later(() => { try { write("update", path, id, data); res(); } catch (e) { rej({ code: e.code, message: e.message }); } })); },
        delete() { return new Promise(res => later(() => { write("delete", path, id); res(); })); },
        onSnapshot(next, error) { return subscribe(() => docSnap(path, id, col(path).get(id)), next, error); },
        collection(sub) { const p = self.path + "/" + sub; segs(p, false); return colRef(p); },
        acquire() { return Promise.resolve({ acquired: true, version: 1, expiresAt: new Date(Date.now() + 30000).toISOString() }); }
      };
      return self;
    }
    function colRef(path) {
      const q = query(path, [], null, 0, null);
      const doc = id => { const i = id == null ? newId() : String(id); segs(path + "/" + i, true); return docRef(path, i); };
      return Object.assign({}, q, { path, doc, add(data) { const r = doc(); return r.set(data).then(() => r); } });
    }
    const db = Object.freeze({ collection(p) { segs(p, false); return colRef(p); }, doc(p) { const s = segs(p, true); return docRef(s.slice(0, -1).join("/"), s[s.length - 1]); } });

    /* ---------- the other capabilities ---------- */
    /* ---------- the other capabilities, on the phone ---------- */
    const user = Object.freeze({ isOwner: async () => true, canEdit: async () => true, can: async () => true,
      id: async () => "phone", me: async () => ({ id: "phone", name: "You", avatarUrl: "", color: "#4A68A8", email: null, isOwner: true, canEdit: true }),
      profiles: async () => ({}), search: async () => [] });

    const MODEL = "claude-sonnet-5-5";
    const fail = (code, text) => ({ code, message: code, text: text || "" });
    async function sample(input, o = {}) {
      let key = "";
      try { key = IWLNative.getKey(); } catch (_) {}
      if (!key) { key = await window.iwlAskKey(); if (!key) throw fail("cancelled"); }
      const messages = typeof input === "string" ? [{ role: "user", content: input }]
        : Array.isArray(input) ? input.map(m => ({ role: m.role === "assistant" ? "assistant" : "user", content: String(m.content) }))
        : (input && input.messages) || [{ role: "user", content: String(input) }];
      while (messages.length && messages[0].role !== "user") messages.shift();
      let res;
      try {
        res = await fetch("https://api.anthropic.com/v1/messages", { method: "POST", signal: o.signal,
          headers: { "content-type": "application/json", "x-api-key": key, "anthropic-version": "2023-06-01",
            "anthropic-dangerous-direct-browser-access": "true" },
          body: JSON.stringify({ model: MODEL, max_tokens: 8000, stream: true,
            system: "You are Claude, answering questions inside a private self-tracking app, Inner Weather Log. Use metric units and the 24-hour clock.",
            messages }) });
      } catch (e) { throw fail(o.signal && o.signal.aborted ? "cancelled" : "upstream_error"); }
      if (!res.ok) {
        let detail = ""; try { detail = (await res.json()).error.message || ""; } catch (_) {}
        if (res.status === 401 || res.status === 403) { try { IWLNative.setKey(""); } catch (_) {} window.iwlAskKey("That API key didn't work. Check it and paste it again."); throw fail("upstream_error"); }
        if (res.status === 429 || res.status === 529) throw fail("rate_limited");
        if (res.status === 413 || /too long|too large|context/i.test(detail)) throw fail("prompt_too_large");
        throw fail("upstream_error");
      }
      const reader = res.body.getReader(), dec = new TextDecoder();
      let buf = "", text = "", stop = null;
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let nl;
          while ((nl = buf.indexOf("\n")) >= 0) {
            const line = buf.slice(0, nl).trim(); buf = buf.slice(nl + 1);
            if (!line.startsWith("data:")) continue;
            let ev; try { ev = JSON.parse(line.slice(5)); } catch (_) { continue; }
            if (ev.type === "content_block_delta" && ev.delta && ev.delta.type === "text_delta") {
              text += ev.delta.text; if (o.onText) o.onText({ text, delta: ev.delta.text });
            } else if (ev.type === "message_delta" && ev.delta) stop = ev.delta.stop_reason;
            else if (ev.type === "error") throw fail(ev.error && ev.error.type === "overloaded_error" ? "rate_limited" : "upstream_error", text);
          }
        }
      } catch (e) {
        if (o.signal && o.signal.aborted) throw fail("cancelled", text);
        throw e && e.code ? e : fail("upstream_error", text);
      }
      if (!text) throw fail(stop === "refusal" ? "refused" : "empty_completion");
      return { text, truncated: stop === "max_tokens", modelTierApplied: "default" };
    }
    sample.json = async (input, o) => JSON.parse((await sample(input, o)).text);
    sample.limits = async () => ({ maxPromptBytes: 256 * 1024 });
    Object.freeze(sample);

    const toDataUrl = data => new Promise((res, rej) => {
      const blob = data instanceof Blob ? data : new Blob([typeof data === "string" ? data : JSON.stringify(data)], { type: "text/plain;charset=utf-8" });
      const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = rej; r.readAsDataURL(blob); });
    const downloads = Object.freeze({ save: async req => { const url = await toDataUrl(req.data);
      IWLNative.saveFile(String(req.filename || "download"), url); return { status: "saved" }; } });

    const NS = { db, user, sample, downloads };
    const memo = new Map();
    window.claude = Object.freeze({ use(name) { if (!memo.has(name)) memo.set(name, new Promise(res => setTimeout(() => res(NS[name] || null), 0))); return memo.get(name); } });
      }
  window.__iwlInstall = install;
})();
/* Inner Weather on the phone: the claude.ai runtime surface (db, user, sample, downloads), kept on this device. */
(function () {
  "use strict";
  // The base styles the claude.ai artifact host puts around every page (ref/live-wrapper-head.html); the page relies on them,
  // e.g. [hidden] must hide sheets whose CSS sets display. The :root safe-area padding is what keeps the page's
  // top clear of the status bar while its background still runs behind the bars.
  const base = document.createElement("style");
  base.textContent = ":root{color-scheme:light;box-sizing:border-box;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}" +
    "html{scroll-padding-top:env(safe-area-inset-top,0px)}body{margin:0;padding:0;font:14px -apple-system,BlinkMacSystemFont,sans-serif;background:#fff;color:#000}" +
    "img{max-width:100%}[hidden]:not([hidden=until-found i]){display:none!important}";
  (document.head || document.documentElement).appendChild(base);
  let pending = null;
  function sheet(message) {
    const dark = matchMedia("(prefers-color-scheme: dark)").matches;
    const bg = dark ? "#0E1830" : "#FFFFFF", ink = dark ? "#EEF2FA" : "#111C35", sub = dark ? "#AFC0DD" : "#3E4A63", line = dark ? "#2A3B63" : "#D5DCE8";
    const wrap = document.createElement("div");
    wrap.id = "iwl-key";
    wrap.setAttribute("role", "dialog"); wrap.setAttribute("aria-modal", "true"); wrap.setAttribute("aria-labelledby", "iwl-key-h");
    wrap.style.cssText = "position:fixed;inset:0;z-index:2147483647;background:rgba(8,14,30,.55);display:flex;align-items:flex-end;justify-content:center;font-family:system-ui,sans-serif";
    wrap.innerHTML = `<form style="box-sizing:border-box;width:100%;max-width:520px;background:${bg};color:${ink};border-radius:22px 22px 0 0;padding:22px 20px calc(20px + env(safe-area-inset-bottom,0px));box-shadow:0 -10px 40px rgba(0,0,0,.25)">
      <h2 id="iwl-key-h" style="margin:0 0 6px;font-size:20px;line-height:1.25">Connect Ask Claude</h2>
      <p style="margin:0 0 14px;color:${sub};font-size:15px;line-height:1.45">${message || "On the phone, Ask Claude uses your own Anthropic API key. It stays on this phone and is only sent to Anthropic."}
        Get one at <a href="https://console.anthropic.com/settings/keys" target="_blank" style="color:inherit;font-weight:600">console.anthropic.com</a>.</p>
      <input id="iwl-key-in" type="password" autocomplete="off" spellcheck="false" placeholder="sk-ant-…" style="box-sizing:border-box;width:100%;font-size:16px;padding:13px 14px;border-radius:12px;border:1px solid ${line};background:transparent;color:inherit">
      <div style="display:flex;gap:10px;margin-top:16px">
        <button type="button" id="iwl-key-x" style="flex:1;min-height:48px;border-radius:999px;border:1px solid ${line};background:transparent;color:inherit;font-size:16px;font-weight:600">Not now</button>
        <button type="submit" style="flex:1;min-height:48px;border-radius:999px;border:0;background:#1D2C4E;color:#fff;font-size:16px;font-weight:600">Save key</button>
      </div></form>`;
    document.body.appendChild(wrap);
    const input = wrap.querySelector("input");
    setTimeout(() => input.focus(), 50);
    return new Promise(res => {
      const done = v => { wrap.remove(); res(v); };
      wrap.querySelector("#iwl-key-x").onclick = () => done("");
      wrap.querySelector("form").onsubmit = e => { e.preventDefault(); const k = input.value.trim(); if (!k) return; try { IWLNative.setKey(k); } catch (_) {} done(k); };
    });
  }
  window.iwlAskKey = function (message) {
    if (pending) return pending;
    const go = () => (pending = sheet(message).then(v => { pending = null; return v; }));
    return document.body ? go() : new Promise(r => addEventListener("DOMContentLoaded", () => r(go())));
  };
  addEventListener("DOMContentLoaded", () => {
    const N = window.IWLNative;
    // Full screen needs env(safe-area-inset-*); an older WebView without it gets margins from the app instead.
    try {
      if (N && N.insets) {
        const ins = JSON.parse(N.insets()), probe = document.createElement("div");
        probe.style.cssText = "position:fixed;visibility:hidden;padding-top:env(safe-area-inset-top,0px)";
        document.body.appendChild(probe);
        const got = parseFloat(getComputedStyle(probe).paddingTop) || 0;
        probe.remove();
        if (ins.top > 0 && got === 0) N.needMargins();
      }
    } catch (_) {}
    // Reminders need a page that reports which parts of the day are open. Without one, park the times (and bring them back later).
    try {
      if (N && N.getReminderTimes && N.setReminderTimes) {
        const reports = document.documentElement.innerHTML.indexOf("setOpenMoments") >= 0;
        const cur = N.getReminderTimes() || "", has = cur && cur !== "{}" && /\d/.test(cur);
        if (!reports && has) { localStorage.setItem("iwl-reminders-parked", cur); N.setReminderTimes("{}"); }
        else if (reports && !has && localStorage.getItem("iwl-reminders-parked")) { N.setReminderTimes(localStorage.getItem("iwl-reminders-parked")); localStorage.removeItem("iwl-reminders-parked"); }
      }
    } catch (_) {}
  });
  let data = null;
  try { const s = IWLStore.load(); if (s) data = JSON.parse(s); } catch (e) { console.error("load failed", e); }
  window.__iwlInstall(data && data.cols ? data : { cols: {} }, {});
})();
