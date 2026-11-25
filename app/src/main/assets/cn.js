// CN.MOVIE helper — تطوير: مهند سات لنك
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

  var timer = null;
  new MutationObserver(function () {
    if (timer) return;
    timer = setTimeout(function () { timer = null; skip(); neon(); }, 300);
  }).observe(document.documentElement, { childList: true, subtree: true });
  skip();
  neon();

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
