// ---------- لقط روابط الجودات والترجمات من ردود الموقع (fetch / XHR) ----------
(function () {
  if (window.__cnHook) return;
  window.__cnHook = true;
  var M = window.__cnMedia = { v: {}, s: {}, log: [], body: {}, fail: [] };
  function failed(u, st) { if (M.fail.length < 30) M.fail.push(st + ' ' + String(u).slice(0, 200)); }
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
    // اسم الفيلم/المسلسل من بيانات الموقع (للعنصر اللي رقمه موجود برابط الصفحة)
    var en = o.en_title || o.enTitle || o.title_en || o.en_name, ar = o.ar_title || o.arTitle || o.title_ar || o.ar_name;
    if ((typeof en === 'string' && en) || (typeof ar === 'string' && ar)) {
      var oid = String(o.nb || o.id || o.videoId || o.video_id || '');
      var here = oid.length >= 3 && location.href.indexOf(oid) !== -1;
      if (here || !M.meta) M.meta = {
        en: en || '', ar: ar || '', year: String(o.year || o.releaseYear || '').slice(0, 4),
        season: o.season || o.seasonNumber || '', ep: o.episodeNummer || o.episodeNumber || o.episode || '',
        series: String(o.kind || o.type || '').toLowerCase(), here: here
      };
    }
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
      return of.apply(window, arguments).then(function (r) {
        if (!r.ok) failed(u, 'FETCH ' + r.status);
        try { var ct = (r.headers.get('content-type') || ''); if (/json|text|javascript|octet|subrip|vtt/.test(ct) || /\.(srt|vtt|ass|ssa)(\?|$)/i.test(String(u))) r.clone().text().then(function (t) { scanText(t, u); }); } catch (e) {}
        return r;
      }, function (err) { failed(u, 'FETCH ERR ' + err); throw err; });
    };
  } catch (e) {}
  try {
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__cnUrl = u;
      this.addEventListener('error', function () { failed(this.__cnUrl, 'XHR ERR'); });
      this.addEventListener('load', function () {
        if (this.status >= 400) failed(this.__cnUrl, 'XHR ' + this.status);
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

// ---------- قراءة الترجمة من المشغّل نفسه (مع التأكد من اللغة) ----------
window.CNSub = function (id, url, wantAr) {
  function send(fmt, data) { try { CNApp.subResult(id, fmt, data); } catch (e) {} }
  function isAr(t) { var m = String(t).slice(0, 20000).match(/[؀-ۿ]/g); return !!m && m.length > 30; }
  function ok(t) { return !wantAr || isAr(t); }
  var B = (window.__cnMedia || {}).body || {};
  function anyArabicBody() { var k = Object.keys(B); for (var i = k.length - 1; i >= 0; i--) if (isAr(B[k[i]])) return B[k[i]]; return null; }
  function tracks() {
    var vids = document.querySelectorAll('video'), best = null;
    for (var i = 0; i < vids.length; i++) {
      var tt = vids[i].textTracks, els = vids[i].querySelectorAll('track');
      for (var j = 0; j < tt.length; j++) {
        var tr = tt[j], src = els[j] ? els[j].src : '';
        if (tr.mode === 'disabled') tr.mode = 'hidden';
        if (!tr.cues || !tr.cues.length) continue;
        var out = [], all = '';
        for (var c = 0; c < tr.cues.length; c++) { out.push([tr.cues[c].startTime, tr.cues[c].endTime, tr.cues[c].text]); if (all.length < 20000) all += tr.cues[c].text; }
        var exact = url && src === url;
        if ((exact || !url || wantAr) && ok(all)) return JSON.stringify(out);
      }
    }
    return best;
  }
  // 1) نفس الملف بالضبط اللي نزّله المشغّل
  if (B[url] && ok(B[url])) { send('text', B[url]); return; }
  // 2) نطلبه من داخل الصفحة
  fetch(url, { credentials: 'include' }).then(function (r) {
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.text();
  }).then(function (t) {
    if (ok(t)) { send('text', t); return; }
    throw new Error('not-arabic');
  }).catch(function (err) {
    // 3) أي ترجمة عربية نزّلها المشغّل، أو نص الترجمة المعروض
    var ab = wantAr ? anyArabicBody() : null;
    if (ab) { send('text', ab); return; }
    var cues = tracks();
    if (cues) { send('cues', cues); return; }
    setTimeout(function () {
      var ab2 = wantAr ? anyArabicBody() : null, c2 = tracks();
      if (ab2) send('text', ab2); else if (c2) send('cues', c2); else send('error', String(err));
    }, 1500);
  });
};

(function () {
  if (window.CN) { window.CN.skip(); return; }

  function txt(e) { return ((e.innerText || e.textContent || '') + '').replace(/\s+/g, ' ').trim(); }
  function visible(e) { return !!(e && e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden'); }
  function clickable(e) { return e.closest('a,button,[role=button],li,mat-list-item') || e; }

  // يدوّر على عنصر ظاهر نصه يطابق إحدى الكلمات ويضغطه
  function clickText(words, exact) {
    var els = document.querySelectorAll('a,button,[role=button],li,span,div,p,label,h1,h2,h3,h4');
    for (var w = 0; w < words.length; w++) {
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        if (e.childElementCount > 3 || !visible(e)) continue;
        var t = txt(e);
        if (!t || t.length > 40) continue;
        if (exact ? t === words[w] : t.indexOf(words[w]) !== -1) { clickable(e).click(); return true; }
      }
    }
    return false;
  }

  // تخطي صفحة «حمّل تطبيق سينمانا» — بحث مرن عن زر الاستمرار
  function isContinue(t) {
    return (t.indexOf('الاستمرار') !== -1 || t.indexOf('استمرار') !== -1 || t.indexOf('متابعة') !== -1) &&
           (t.indexOf('موقع') !== -1 || t.indexOf('المتصفح') !== -1) ||
           /continue\s+(to|on|in|with)\s+(the\s+)?(web|site|browser)/i.test(t);
  }
  function fire(el) {
    var target = clickable(el);
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (n) {
      try { target.dispatchEvent(new MouseEvent(n, { bubbles: true, cancelable: true, view: window })); } catch (e) {}
    });
    target.click();
  }
  function skip() {
    var els = document.querySelectorAll('button,a,[role=button],span,div,p');
    var best = null;
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (!visible(e)) continue;
      var t = txt(e);
      if (!t || t.length > 40 || !isContinue(t)) continue;
      if (!best || best.contains(e)) best = e; // أصغر عنصر يحمل النص
    }
    if (best) { fire(best); return true; }
    return false;
  }

  // ---------- ثيم النيون فوق الموقع ----------
  var style = document.createElement('style');
  style.id = 'cn-neon';
  style.textContent =
    'html,body{background:#07060F!important}' +
    '::selection{background:#FF2BD6;color:#fff}' +
    'input:focus,textarea:focus,select:focus{border-color:#22E4FF!important;box-shadow:0 0 10px rgba(34,228,255,.55)!important;outline:none!important}' +
    '::-webkit-scrollbar{width:0;height:0}' +
    '.cn-neon-bg{background:linear-gradient(90deg,#FF2BD6,#A23BFF)!important;box-shadow:0 0 14px rgba(255,43,214,.6)!important;border-color:#FF2BD6!important}' +
    '.cn-neon-text{color:#FF2BD6!important;text-shadow:0 0 8px rgba(255,43,214,.7)}' +
    '.cn-neon-border{border-color:#22E4FF!important;box-shadow:0 0 8px rgba(34,228,255,.45)}' +
    '.cn-dark-bg{background-color:#0E0C1A!important}';
  (document.head || document.documentElement).appendChild(style);

  function rgb(c) { var m = c && c.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/); return m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : +m[4]] : null; }
  function isRed(c) { return c && c[3] > 0.3 && c[0] >= 150 && c[1] <= 80 && c[2] <= 80; }
  function isNavy(c) { return c && c[3] > 0.5 && c[0] <= 40 && c[1] <= 45 && c[2] <= 60 && c[2] > c[0] + 4; }
  function neon() {
    var els = document.body ? document.body.querySelectorAll('*:not([data-cn])') : [];
    for (var i = 0; i < els.length && i < 4000; i++) {
      var e = els[i];
      e.setAttribute('data-cn', '1');
      if (e.tagName === 'IMG' || e.tagName === 'VIDEO' || e.tagName === 'IFRAME') continue;
      var cs = getComputedStyle(e);
      if (isRed(rgb(cs.backgroundColor))) e.classList.add('cn-neon-bg');
      else if (isNavy(rgb(cs.backgroundColor))) e.classList.add('cn-dark-bg');
      if (isRed(rgb(cs.color))) e.classList.add('cn-neon-text');
      if (parseFloat(cs.borderTopWidth) > 0 && isRed(rgb(cs.borderTopColor))) e.classList.add('cn-neon-border');
    }
  }

  // ---------- إشعار التطبيق إن الصفحة جاهزة (حتى تختفي شاشة البداية بالوقت الصح) ----------
  var readySent = false, t0 = Date.now(), skippedAt = 0;
  function ready() { if (readySent) return; readySent = true; try { CNApp.ready(); } catch (e) {} }
  var poll = setInterval(function () {
    if (skip()) skippedAt = Date.now();
    var age = Date.now() - t0;
    if (skippedAt && Date.now() - skippedAt > 600) { ready(); }
    else if (!skippedAt && age > 1200 && document.body && txt(document.body).length > 150) { ready(); }
    if (age > 8000) { clearInterval(poll); ready(); }
  }, 200);

  // ---------- اسم وشعار CN.MOVIE بدل سينمانا ----------
  // بالـ CSS فقط: ما نغيّر عناصر الموقع (تغييرها جان يخرّب الصفحة بعد تسجيل الدخول)
  var bs = document.createElement('style');
  bs.textContent =
    '.cn-brand{font-size:0!important;color:transparent!important;white-space:nowrap}' +
    '.cn-brand::before{content:"";display:inline-block;width:30px;height:30px;vertical-align:middle;margin-inline-end:8px;border-radius:8px;' +
      (window.__cnLogo ? 'background:url(' + window.__cnLogo + ') center/contain no-repeat;' : '') + '}' +
    '.cn-brand::after{content:"CN.MOVIE";direction:ltr;unicode-bidi:isolate;font-size:22px;font-weight:800;letter-spacing:1px;vertical-align:middle;' +
      'background:linear-gradient(90deg,#fff 0 38%,#FF2BD6 38%);-webkit-background-clip:text;background-clip:text;color:transparent;' +
      'filter:drop-shadow(0 0 6px rgba(34,228,255,.6))}' +
    '.cn-oldlogo{display:none!important}';
  (document.head || document.documentElement).appendChild(bs);
  function brand() {
    var els = document.querySelectorAll('span,div,h1,h2,h3,h4,p,a,b,strong');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.childElementCount !== 0 || e.classList.contains('cn-brand')) continue;
      var t = txt(e);
      if (t !== 'سينمانا' && t !== 'سينمــانا' && t !== 'سينمـــانا' && t.toLowerCase() !== 'cinemana') continue;
      e.classList.add('cn-brand');
      var box = e.parentElement;
      for (var up = 0; up < 3 && box; up++) {
        var im = box.querySelector('img:not(.cn-oldlogo),svg:not(.cn-oldlogo)');
        if (im && !im.closest('.cn-brand')) {
          var r = im.getBoundingClientRect();
          if (r.width && r.width < 120) im.classList.add('cn-oldlogo');
          break;
        }
        box = box.parentElement;
      }
    }
  }
  // ---------- حذف عناصر ما نحتاجها من القائمة ----------
  var HIDE = ['مركز المساعدة', 'تطبيقات شبكتي'];
  function hideEl(e) { if (e) e.style.setProperty('display', 'none', 'important'); }
  function prune() {
    var els = document.querySelectorAll('a,button,li,span,div,p');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.childElementCount > 2) continue;
      var t = txt(e);
      if (HIDE.indexOf(t) !== -1) hideEl(e.closest('li,a,button,mat-list-item,[role=listitem],[role=menuitem]') || e.parentElement);
    }
    var soc = document.querySelectorAll('a[href*="twitter.com"],a[href*="x.com/"],a[href*="youtube.com"],a[href*="youtu.be"],a[href*="t.me/"],a[href*="telegram"],a[href*="instagram.com"],a[href*="facebook.com"],a[href*="tiktok.com"]');
    var parents = [];
    for (var j = 0; j < soc.length; j++) {
      if (txt(soc[j]).length > 2 || soc[j].closest('form')) continue;
      hideEl(soc[j]);
      if (parents.indexOf(soc[j].parentElement) === -1) parents.push(soc[j].parentElement);
    }
    parents.forEach(function (p) {
      if (!p) return;
      var vis = false;
      for (var k = 0; k < p.children.length; k++) if (p.children[k].style.display !== 'none' && txt(p.children[k])) vis = true;
      if (!vis) hideEl(p);
    });
  }

  // ---------- يكمل من وين وقفت ----------
  function fmtT(s) { s = Math.floor(s); var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (x < 10 ? '0' : '') + x; }
  function resume() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (v.__cnRes) continue;
      v.__cnRes = true;
      (function (v) {
        var last = 0, restored = false;
        function key() { return 'cnpos:' + location.pathname + ':' + Math.round(v.duration || 0); }
        function tryRestore() {
          if (restored || !v.duration || !isFinite(v.duration)) return;
          restored = true;
          var p = 0; try { p = parseFloat(localStorage.getItem(key())) || 0; } catch (e) {}
          if (p > 30 && p < v.duration - 90 && v.currentTime < 10) {
            v.currentTime = p;
            try { CNApp.toast('كمّلنا من ' + fmtT(p)); } catch (e) {}
          }
        }
        v.addEventListener('loadedmetadata', tryRestore);
        v.addEventListener('playing', tryRestore);
        v.addEventListener('timeupdate', function () {
          if (!v.duration || !isFinite(v.duration) || Date.now() - last < 5000) return;
          last = Date.now();
          try {
            if (v.currentTime > v.duration - 60) localStorage.removeItem(key());
            else if (v.currentTime > 30) localStorage.setItem(key(), String(v.currentTime));
          } catch (e) {}
        });
        tryRestore();
      })(v);
    }
  }

  var timer = null;
  new MutationObserver(function () {
    if (timer) return;
    timer = setTimeout(function () { timer = null; skip(); neon(); brand(); prune(); resume(); }, 300);
  }).observe(document.documentElement, { childList: true, subtree: true });
  skip();
  neon();
  brand();
  prune();
  resume();

  function firstVisible(sels) {
    for (var i = 0; i < sels.length; i++) {
      var list = document.querySelectorAll(sels[i]);
      for (var j = 0; j < list.length; j++) if (visible(list[j])) return list[j];
    }
    return null;
  }

  window.CN = {
    skip: skip,
    search: function () {
      var inp = firstVisible(['input[type=search]', 'input[placeholder*="بحث"]', 'input[placeholder*="earch"]']);
      if (inp) { inp.scrollIntoView({ block: 'center' }); inp.focus(); inp.click(); return 'ok'; }
      if (clickText(['بحث عن فلم', 'بحث', 'Search'], false)) {
        setTimeout(function () {
          var i2 = firstVisible(['input[type=search]', 'input[type=text]']);
          if (i2) { i2.focus(); i2.click(); }
        }, 400);
        return 'ok';
      }
      return 'no';
    },
    cats: function () {
      if (clickText(['الأقسام', 'الاقسام', 'التصنيفات', 'Categories'], true)) return 'ok';
      var menu = firstVisible([
        '[aria-label*="menu" i]', '[class*="hamburger" i]', '[class*="burger" i]',
        '[class*="navbar-toggler" i]', '[class*="menu-toggle" i]', '[class*="menu-btn" i]',
        '[class*="menu-icon" i]', 'button[class*="menu" i]'
      ]);
      if (!menu) {
        var icons = document.querySelectorAll('mat-icon,i,span');
        for (var k = 0; k < icons.length; k++) if (txt(icons[k]) === 'menu' && visible(icons[k])) { menu = icons[k]; break; }
      }
      if (menu) {
        clickable(menu).click();
        setTimeout(function () { clickText(['الأقسام', 'الاقسام', 'التصنيفات', 'Categories'], true); }, 400);
        return 'ok';
      }
      return 'no';
    },
    account: function () {
      if (clickText(['حسابي', 'الحساب', 'الملف الشخصي', 'تسجيل الدخول', 'My account', 'Login', 'Sign in'], false)) return 'ok';
      var u = firstVisible(['[class*="account" i]', '[class*="profile" i]', '[class*="avatar" i]', '[class*="user" i]']);
      if (u) { clickable(u).click(); return 'ok'; }
      return 'no';
    }
  };
})();

