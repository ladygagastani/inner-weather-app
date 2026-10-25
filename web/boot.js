/* Inner Weather on the phone: the claude.ai runtime surface (db, user, sample, downloads), kept on this device. */
(function () {
  "use strict";
  // The base styles the claude.ai artifact host puts around every page (ref/live-wrapper-head.html); the page relies on them,
  // e.g. [hidden] must hide sheets whose CSS sets display.
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
  let data = null;
  try { const s = IWLStore.load(); if (s) data = JSON.parse(s); } catch (e) { console.error("load failed", e); }
  window.__iwlInstall(data && data.cols ? data : { cols: {} }, {});
})();
