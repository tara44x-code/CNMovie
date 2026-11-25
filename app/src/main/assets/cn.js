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

  // تخطي صفحة «حمّل تطبيق سينمانا»
  var SKIP = ['الاستمرار في الموقع', 'الاستمرار بالموقع', 'Continue to website', 'Continue on website', 'Continue to site'];
  function skip() { return clickText(SKIP, true); }

  var timer = null;
  new MutationObserver(function () {
    if (timer) return;
    timer = setTimeout(function () { timer = null; skip(); }, 250);
  }).observe(document.documentElement, { childList: true, subtree: true });
  skip();

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
