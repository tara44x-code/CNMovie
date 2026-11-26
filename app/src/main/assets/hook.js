// ---------- لقط روابط الجودات والترجمات من ردود الموقع (fetch / XHR) ----------
(function () {
  if (window.__cnHook) return;
  window.__cnHook = true;
  var M = window.__cnMedia = { v: {}, s: {}, log: [], body: {} };
  var RX = /https?:\/\/[^"'\s\\<>]+?\.(mp4|mkv|webm|m4v|srt|vtt|ass|ssa)(\?[^"'\s\\<>]*)?/gi;
  function add(u, label) {
    var p = u.split('?')[0].toLowerCase();
    if (/\.(srt|vtt|ass|ssa)$/.test(p)) { if (!M.s[u] || label) M.s[u] = label || M.s[u] || ''; }
    else if (/\.(mp4|mkv|webm|m4v)$/.test(p)) { if (!M.v[u] || label) M.v[u] = label || M.v[u] || ''; }
  }
  var LKEYS = ['resolution', 'quality', 'res', 'name', 'label', 'title', 'lang', 'language', 'type', 'extention', 'extension'];
  function walk(o, d) {
    if (!o || d > 12) return;
    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walk(o[i], d + 1); return; }
    if (typeof o !== 'object') return;
    var label = '';
    for (var k = 0; k < LKEYS.length && !label; k++) {
      var x = o[LKEYS[k]];
      if ((typeof x === 'string' || typeof x === 'number') && String(x).length < 25 && String(x).indexOf('http') !== 0) label = String(x);
    }
    for (var key in o) {
      var v = o[key];
      if (typeof v === 'string' && v.indexOf('http') === 0) { RX.lastIndex = 0; if (RX.test(v)) add(v, label); }
      else if (v && typeof v === 'object') walk(v, d + 1);
    }
  }
  function looksSub(t) {
    var h = t.slice(0, 600);
    return /^\uFEFF?WEBVTT/.test(h) || h.indexOf('[Script Info]') !== -1 ||
      /\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}/.test(h);
  }
  function scanText(t, url) {
    if (!t || t.length > 3000000) return;
    if (typeof t === 'string' && looksSub(t)) {
      var keys = Object.keys(M.body); if (keys.length > 8) delete M.body[keys[0]];
      M.body[String(url)] = t;
      M.s[String(url)] = M.s[String(url)] || (/[\u0600-\u06FF]/.test(t.slice(0, 4000)) ? 'عربي' : '');
      if (M.log.length < 40) M.log.push('SUBBODY ' + String(url).slice(0, 150));
      return;
    }
    try { walk(JSON.parse(t), 0); } catch (e) { var m; RX.lastIndex = 0; while ((m = RX.exec(t))) add(m[0], ''); }
    if (M.log.length < 40) M.log.push(String(url).slice(0, 160));
  }
  try {
    var of = window.fetch;
    if (of) window.fetch = function () {
      var u = arguments[0] && (arguments[0].url || arguments[0]);
      return of.apply(this, arguments).then(function (r) {
        try { var ct = (r.headers.get('content-type') || ''); if (/json|text|javascript|octet|subrip|vtt/.test(ct) || /\.(srt|vtt|ass|ssa)(\?|$)/i.test(String(u))) r.clone().text().then(function (t) { scanText(t, u); }); } catch (e) {}
        return r;
      });
    };
  } catch (e) {}
  try {
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__cnUrl = u;
      this.addEventListener('load', function () {
        try {
          var rt = this.responseType;
          if (rt === '' || rt === 'text') scanText(this.responseText, this.__cnUrl);
          else if (rt === 'json') { walk(this.response, 0); if (M.log.length < 40) M.log.push(String(this.__cnUrl).slice(0, 160)); }
        } catch (e) {}
      });
      return oo.apply(this, arguments);
    };
  } catch (e) {}
})();


// ---------- تخطي المقدمة تلقائياً (يشتغل بكل إطار، ومنها إطار المشغّل) ----------
(function () {
  if (window.__cnSkipLoop) return;
  window.__cnSkipLoop = true;
  function opt() { try { return (window.top.__cnOpt || window.__cnOpt || { skip: true }); } catch (e) { return window.__cnOpt || { skip: true }; } }
  function norm(t) { return String(t || '').replace(/[ً-ٟـ]/g, '').replace(/\s+/g, ' ').trim(); }
  function isSkip(t) { t = norm(t); return t.length < 30 && ((t.indexOf('تخطي') !== -1 || t.indexOf('تجاوز') !== -1) && t.indexOf('مقدم') !== -1 || /skip\s*(the\s*)?intro/i.test(t) || /skip\s*opening/i.test(t)); }
  function vis(e) { return !!(e && e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).opacity !== '0'); }
  var last = 0;
  setInterval(function () {
    if (!opt().skip || Date.now() - last < 15000 || !document.body) return;
    var els = document.querySelectorAll('button,a,[role=button],div,span');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.childElementCount > 3) continue;
      if (!isSkip(e.innerText || e.textContent) || !vis(e)) continue;
      var tg = e.closest('button,a,[role=button]') || e;
      last = Date.now();
      ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (n) {
        try { tg.dispatchEvent(new MouseEvent(n, { bubbles: true, cancelable: true, view: window })); } catch (x) {}
      });
      tg.click();
      try { CNApp.toast('تخطينا المقدمة'); } catch (x) {}
      return;
    }
  }, 1000);
})();
