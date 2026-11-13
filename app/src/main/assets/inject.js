/* ================================================================
   SL POST STAMPS - injected behaviour (v4)

   Runs at onPageFinished, after page-detector.js has set
   window.__SLP_PAGE_TYPE__ and tagged <body>. All styling lives in
   inject.css; this file only adds behaviour and the few wrapper
   elements that behaviour needs.

   The page belongs to a third party. Every selector is an assumption
   about its markup, so each feature looks its elements up defensively
   and does nothing when they are missing.
   ================================================================ */

(function () {
'use strict';

/* ──────── RE-ENTRANCY ────────
   onPageFinished fires again for the same document on fragment
   navigations, and the app triggers them on purpose (#slp-browse from the
   Browse tab, /login#registetModal from Sign up, #model_<id> when the
   site opens its add-to-cart sheet). Anything registered at document
   level goes through slpOnce(); per-element work marks the element it
   has already handled. */
function slpOnce(key, fn) {
  var flag = '__slpOnce_' + key;
  if (window[flag]) return;
  window[flag] = true;
  safely(key, fn);
}

function safely(label, fn) {
  try { fn(); } catch (e) { log(label + ' failed: ' + e.message); }
}

function log(message) {
  var b = window.SLPBridge;
  if (b && typeof b.log === 'function') { try { b.log(String(message)); } catch (ignored) {} }
}

function $1(sel, root) { return (root || document).querySelector(sel); }
function $all(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
function el(tag, className, text) {
  var e = document.createElement(tag);
  if (className) e.className = className;
  if (text != null) e.textContent = text;
  return e;
}

var pageType = window.__SLP_PAGE_TYPE__ || 'UNKNOWN';
var jq = window.jQuery;

/* ──────── VIEWPORT ──────── */
safely('viewport', function () {
  var head = document.head || document.documentElement;
  var vp = $1('meta[name="viewport"]');
  if (!vp) { vp = el('meta'); vp.name = 'viewport'; head.appendChild(vp); }
  vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
});

/* ──────── TOUCH RIPPLE ──────── */
slpOnce('ripple', function () {
  document.addEventListener('click', function (e) {
    var t = e.target.closest && e.target.closest('.btn, button[type="submit"], input[type="submit"]');
    if (!t || t.tagName === 'INPUT') return;   // <input> cannot hold a child
    var r = t.getBoundingClientRect();
    var dot = el('span', 'slp-ripple-dot');
    dot.style.top = (e.clientY - r.top) + 'px';
    dot.style.left = (e.clientX - r.left) + 'px';
    t.classList.add('slp-ripple-host');
    t.appendChild(dot);
    setTimeout(function () { dot.remove(); }, 500);
  }, false);
});

/* ──────── IMAGE FADE-IN ────────
   The listeners are attached whether or not the image has settled: the
   add-to-cart sheet reuses one <img> and swaps its src, so an image that
   was "broken" a moment ago (empty src) must be able to recover. The
   previous version set a permanent inline opacity and left that image
   at 30%. */
function watchImages(root) {
  $all('img:not(.slp-img)', root).forEach(function (img) {
    img.classList.add('slp-img');
    img.addEventListener('load', function () {
      img.classList.remove('slp-img-loading', 'slp-img-broken');
    });
    img.addEventListener('error', function () {
      img.classList.remove('slp-img-loading');
      if (img.getAttribute('src')) img.classList.add('slp-img-broken');
    });
    if (!img.getAttribute('src')) return;
    if (!img.complete) img.classList.add('slp-img-loading');
    else if (img.naturalWidth === 0) img.classList.add('slp-img-broken');
  });
}

/* ──────── QUANTITY STEPPERS ────────
   Wraps the site's quantity inputs (input.input-qty: the add-to-cart
   sheet, the product page and the cart) in − / + buttons. */
function addSteppers(root) {
  $all('input.input-qty', root).forEach(function (input) {
    if (input.dataset.slpStep) return;
    input.dataset.slpStep = '1';
    input.setAttribute('inputmode', 'numeric');

    var wrap = el('span', 'slp-stepper');
    input.parentNode.insertBefore(wrap, input);
    var dec = el('button', null, '−');
    var inc = el('button', null, '+');
    dec.type = inc.type = 'button';
    dec.setAttribute('aria-label', 'Decrease quantity');
    inc.setAttribute('aria-label', 'Increase quantity');
    wrap.appendChild(dec);
    wrap.appendChild(input);
    wrap.appendChild(inc);

    function change(by) {
      if (input.disabled) return;
      // The popup's "Mint" input declares min="1", but 0 is a real value
      // there (cancelled-only orders), and the site itself fills it with 0.
      var max = parseInt(input.max, 10);
      if (isNaN(max)) max = 9999;
      var v = parseInt(input.value, 10);
      if (isNaN(v)) v = 0;
      v = Math.min(max, Math.max(0, v + by));
      input.value = v;
      // The site validates stock through a "donetyping" plugin bound to
      // keypress/paste/blur - it never hears `change`. Fire what it
      // listens for, so over-ordering is flagged from the buttons too.
      ['input', 'keypress', 'change'].forEach(function (type) {
        input.dispatchEvent(new Event(type, { bubbles: true }));
      });
    }
    dec.addEventListener('click', function () { change(-1); });
    inc.addEventListener('click', function () { change(1); });
  });
}

/* ──────── DOM CHANGES ────────
   Search results and the cart sheet arrive over AJAX. */
function enhanceAdded(root) {
  watchImages(root);
  addSteppers(root);
}
enhanceAdded(document);
slpOnce('observer', function () {
  if (!window.MutationObserver || !document.body) return;
  new MutationObserver(function (muts) {
    for (var i = 0; i < muts.length; i++) {
      var added = muts[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        if (added[j].nodeType === 1 && !added[j].classList.contains('slp-ripple-dot')) {
          safely('enhanceAdded', enhanceAdded.bind(null, added[j]));
        }
      }
    }
  }).observe(document.body, { childList: true, subtree: true });
});

/* ──────── APP NAVIGATION HASHES ────────
   #slp-browse      Browse tab: scroll to the catalogue
   #registetModal   Sign up: scroll to the register form on /login */
function handleHash() {
  var hash = location.hash || '';
  var target = null;
  if (hash === '#slp-browse') {
    target = $1('.category-tab, .nav-tabs, #resultPanel');
  } else if (hash.indexOf('registetModal') !== -1) {
    target = document.getElementById('registetModal');
  }
  if (target) {
    setTimeout(function () { target.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, 250);
  }
}
handleHash();
slpOnce('hashchange', function () { window.addEventListener('hashchange', handleHash); });

/* ════════════════════════════════════════════════════
   HOME
   ════════════════════════════════════════════════════ */

function enhanceHome() {
  /* The server-flash strip is whitespace-only on a normal load, which
     defeats :empty. */
  var flash = $1('.addHomeFlash');
  if (flash) flash.classList.toggle('slp-empty', !flash.textContent.trim() && !flash.children.length);

  enhanceCarousel();
  enhanceSearch();
}

/* Slides: swipe to change, tap to open - but only when the slide's
   button holds a real URL. Many hold free text ("SPECIAL ANNONCEMENT"),
   which the site renders as a dead relative link. */
function enhanceCarousel() {
  var carousel = document.getElementById('slider-carousel');
  if (!carousel || carousel.dataset.slpCarousel) return;
  carousel.dataset.slpCarousel = '1';

  $all('.item', carousel).forEach(function (item) {
    var link = $1('a.btn', item);
    var href = link ? (link.getAttribute('href') || '').trim() : '';
    if (!/^(https?:\/\/|\/)/i.test(href)) return;
    var img = $1('img.slider-image', item);
    if (!img) return;
    img.style.cursor = 'pointer';
    img.addEventListener('click', function () { location.href = sameSiteUrl(href); });
  });

  var x0 = 0, y0 = 0;
  carousel.addEventListener('touchstart', function (e) {
    x0 = e.touches[0].clientX; y0 = e.touches[0].clientY;
  }, { passive: true });
  carousel.addEventListener('touchend', function (e) {
    var dx = e.changedTouches[0].clientX - x0;
    var dy = e.changedTouches[0].clientY - y0;
    if (Math.abs(dx) < 40 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    if (jq && jq.fn && jq.fn.carousel) jq(carousel).carousel(dx < 0 ? 'next' : 'prev');
  }, { passive: true });
}

/* Slide links are written against www.stamps.slpost.gov.lk. That is a
   different cookie origin: following one silently drops the session and
   the cart. Keep the path, stay on the host we are on. */
function sameSiteUrl(href) {
  try {
    var u = new URL(href, location.href);
    if (/(^|\.)stamps\.slpost\.gov\.lk$/i.test(u.hostname)) {
      return location.origin + u.pathname + u.search + u.hash;
    }
    return u.href;
  } catch (e) { return href; }
}

/* Search: the site shows every filter at once, a full screen of controls
   above the catalogue. Keep the keyword field and the Search button on
   one line and fold the rest behind a "Filters" toggle.

   Elements are MOVED, never recreated: the site's handlers are bound to
   #searchedKeyword and #searchButton directly and travel with them. */
function enhanceSearch() {
  var menu = document.getElementById('search-menu');
  var keyword = document.getElementById('searchedKeyword');
  var button = document.getElementById('searchButton');
  if (!menu || !keyword || !button || menu.dataset.slpSearch) return;
  menu.dataset.slpSearch = '1';

  var keywordGroup = keyword.closest('.form-group');

  var filters = el('div', 'slp-filters');
  filters.hidden = true;
  filters.id = 'slp-filters';
  $all(':scope > .panel-group, :scope > form', menu).forEach(function (n) { filters.appendChild(n); });

  var row = el('div', 'slp-search-row');
  keyword.type = 'search';
  keyword.setAttribute('enterkeyhint', 'search');
  keyword.setAttribute('aria-label', 'Search the catalogue');
  keyword.placeholder = 'Search by name, CSL, bulletin # or date';
  row.appendChild(keyword);
  row.appendChild(button);
  if (keywordGroup) keywordGroup.style.display = 'none';

  var toggle = el('button', 'slp-filter-toggle');
  toggle.type = 'button';
  toggle.setAttribute('aria-controls', 'slp-filters');
  toggle.setAttribute('aria-expanded', 'false');
  toggle.appendChild(document.createTextNode('Filters'));
  var count = el('span', 'slp-filter-count');
  toggle.appendChild(count);
  toggle.addEventListener('click', function () {
    filters.hidden = !filters.hidden;
    toggle.setAttribute('aria-expanded', String(!filters.hidden));
  });

  menu.appendChild(row);
  menu.appendChild(toggle);
  menu.appendChild(filters);

  function refreshCount() {
    var n = $all('.search-category-check', filters).filter(function (c) { return c.checked; }).length;
    var year = document.getElementById('searchedYears');
    if (year && year.value) n++;
    count.textContent = n ? String(n) : '';
  }
  filters.addEventListener('change', refreshCount);
  refreshCount();

  // Searching scrolls to the results; get the keyboard out of the way.
  button.addEventListener('click', function () { keyword.blur(); });
  keyword.addEventListener('keydown', function (e) { if (e.keyCode === 13) keyword.blur(); });
}

/* The add-to-cart sheet opens with whatever is already in the cart for
   that item - 0 and 0 for anything new - so "Add to Cart" on a fresh
   item used to add a line with a quantity of zero. Default a new item
   to one mint copy, as the product page does. */
slpOnce('sheetDefaults', function () {
  if (!jq || !jq.fn || typeof jq(document).ajaxSuccess !== 'function') return;
  jq(document).ajaxSuccess(function (event, xhr, settings) {
    if (!settings || !/\/popupGet/i.test(settings.url || '')) return;
    var mint = document.getElementById('model_mint_qty');
    var cancelled = document.getElementById('model_cancelled_qty');
    if (!mint) return;
    var m = parseInt(mint.value, 10) || 0;
    var c = cancelled ? (parseInt(cancelled.value, 10) || 0) : 0;
    if (m === 0 && c === 0) mint.value = 1;
    if (cancelled && cancelled.value === '') cancelled.value = 0;
  });
});

/* ════════════════════════════════════════════════════
   PRODUCT DETAIL
   ════════════════════════════════════════════════════ */

function enhanceProductDetail() {
  /* Tap the hero image for a full-screen, pinch-zoomable view. */
  var hero = $1('.product-view > img');
  if (hero && !hero.dataset.slpZoom) {
    hero.dataset.slpZoom = '1';
    hero.addEventListener('click', function () {
      var overlay = el('div', 'slp-overlay');
      var big = el('img');
      big.src = hero.getAttribute('data-zoom-image') || hero.src;
      big.alt = hero.alt || '';
      var close = el('button', null, '×');
      close.type = 'button';
      close.setAttribute('aria-label', 'Close');
      overlay.appendChild(big);
      overlay.appendChild(close);
      overlay.addEventListener('click', function () { overlay.remove(); });
      document.body.appendChild(overlay);
    });
  }

  /* "Related Items" is a bare <span> in a column, followed by sibling
     card columns in the same .row. Mark the row as a card grid. */
  $all('#main-content .row > .column > span').forEach(function (span) {
    if (!/related items/i.test(span.textContent)) return;
    span.classList.add('slp-section-title');
    var rowEl = span.parentNode.parentNode;
    if ($1('.product-image-wrapper', rowEl)) rowEl.classList.add('slp-card-grid');
  });

  var cancelled = $1('#product-view-table .cancelled-qty');
  if (cancelled && cancelled.value === '') cancelled.value = 0;
}

/* ════════════════════════════════════════════════════
   CART
   ════════════════════════════════════════════════════ */

function enhanceCart() {
  var form = document.getElementById('cart_view_form');
  if (!form) return;   // empty cart: nothing to do, CSS handles it

  /* Summary rows (sub total, postage, grand total) are the body rows
     that start with a <th>. Tag them, and pick out the label and the
     value so CSS can lay them out as "label ...... value". */
  var table = $1(':scope > .row table', form);
  if (table && table.tBodies[0]) {
    var sums = Array.prototype.filter.call(table.tBodies[0].rows, function (r) {
      return r.id !== 'no-shipping-message' && r.cells.length && r.cells[0].tagName === 'TH';
    });
    sums.forEach(function (r, i) {
      r.classList.add('slp-sum');
      if (i === 0) r.classList.add('slp-sum-first');
      if (i === sums.length - 1) r.classList.add('slp-sum-last');
      var cells = Array.prototype.filter.call(r.cells, function (c) {
        return !c.classList.contains('image-holder');
      });
      var label = cells[0];
      var value = null;
      for (var k = cells.length - 1; k > 0; k--) {
        if (cells[k].textContent.replace(/ /g, ' ').trim() || cells[k].querySelector('[id]')) {
          value = cells[k];
          break;
        }
      }
      cells.forEach(function (c) {
        c.classList.remove('slp-sum-value', 'slp-sum-hide');
        if (c === value) c.classList.add('slp-sum-value');
        else if (c !== label) c.classList.add('slp-sum-hide');
      });
    });
  }

  /* Quantities are only saved by "Update Cart". Say so once one changes. */
  var update = document.getElementById('submit-ajax');
  var actions = $1(':scope > .row.no-side-margin:last-of-type', form);
  if (update && actions && !form.dataset.slpDirty) {
    form.dataset.slpDirty = '1';
    var hint = el('p', 'slp-hint', 'Quantities changed — tap Update Cart to save them.');
    hint.hidden = true;
    hint.setAttribute('role', 'status');
    actions.parentNode.insertBefore(hint, actions);
    var markDirty = function (e) {
      if (!e.target.classList || !e.target.classList.contains('input-qty')) return;
      hint.hidden = false;
      update.classList.add('slp-dirty');
    };
    form.addEventListener('input', markDirty);
    form.addEventListener('change', markDirty);
  }
}

/* "Clear cart" is a plain link that empties the cart the moment it is
   tapped, and it sits next to "More Items". Ask first. */
slpOnce('confirmClear', function () {
  document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[href*="clear-cart"]');
    if (!a) return;
    if (!window.confirm('Remove every item from your cart?')) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);
});

/* ════════════════════════════════════════════════════
   SIGN IN / REGISTER
   ════════════════════════════════════════════════════ */

/* On /login the only sign-in form (.home-login) sits inside the navbar,
   which the app hides. Lift it into the page, above the register block,
   as its own card. /users/login-view has a proper form and needs none
   of this. */
function relocateLoginForm() {
  var reg = document.getElementById('registetModal');
  if (!reg || !reg.parentNode || $1('.slp-login-card')) return;
  var home = $1('nav.navbar .home-login');
  if (!home) return;

  var card = el('div', 'slp-login-card');
  card.appendChild(el('h2', null, 'Sign in'));
  card.appendChild(home);
  var forgot = el('a', 'slp-forgot', 'Forgot password?');
  forgot.href = '/users/password';
  card.appendChild(forgot);
  reg.parentNode.insertBefore(card, reg);

  // The navbar form opts out of autofill; a sign-in form should not.
  var email = $1('input[name="email"]', card);
  var pw = $1('input[type="password"]', card);
  if (email) { email.type = 'email'; email.setAttribute('autocomplete', 'username'); email.placeholder = 'E-mail'; }
  if (pw) pw.setAttribute('autocomplete', 'current-password');
}

function addPwToggles() {
  $all('input[type="password"]').forEach(function (pw) {
    if (pw.dataset.slpPw) return;
    pw.dataset.slpPw = '1';
    var wrap = el('span', 'slp-pw-wrap');
    pw.parentNode.insertBefore(wrap, pw);
    wrap.appendChild(pw);
    var btn = el('button', 'slp-pw-btn', 'Show');
    btn.type = 'button';
    btn.setAttribute('aria-label', 'Show password');
    btn.addEventListener('click', function () {
      var show = pw.type === 'password';
      pw.type = show ? 'text' : 'password';
      btn.textContent = show ? 'Hide' : 'Show';
      btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    });
    wrap.appendChild(btn);
  });
}

/* Strength meter on the register form's password only - not on the
   sign-in card that shares the page. */
function addStrengthMeter() {
  var pw = $1('#registetModal input[type="password"]');
  if (!pw || pw.dataset.slpMeter) return;
  pw.dataset.slpMeter = '1';
  pw.setAttribute('autocomplete', 'new-password');
  var meter = el('div', 'slp-pw-meter');
  meter.hidden = true;
  var track = el('i');
  var bar = el('b');
  var text = el('span');
  track.appendChild(bar);
  meter.appendChild(track);
  meter.appendChild(text);
  var anchor = pw.closest('.slp-pw-wrap') || pw;
  anchor.parentNode.insertBefore(meter, anchor.nextSibling);
  var LABELS = ['', 'Weak', 'Fair', 'Fair', 'Strong', 'Very strong'];
  pw.addEventListener('input', function () {
    var v = pw.value, s = 0;
    if (v.length >= 6) s++;
    if (v.length >= 10) s++;
    if (/[A-Z]/.test(v) && /[a-z]/.test(v)) s++;
    if (/[0-9]/.test(v)) s++;
    if (/[^A-Za-z0-9]/.test(v)) s++;
    meter.hidden = !v;
    meter.setAttribute('data-level', String(s));
    bar.style.width = (s * 20) + '%';
    text.textContent = LABELS[s] || '';
  });
}

function enhanceAuth() {
  relocateLoginForm();

  var regTitle = $1('#registetModal .modal-title');
  if (regTitle) regTitle.textContent = 'Create account';
  var loginTitle = $1('#loginModal .modal-title');
  if (loginTitle) loginTitle.textContent = 'Sign in';

  addPwToggles();
  addStrengthMeter();

  /* Pre-tick "Remember me" so the site keeps the session alive longer;
     the user can still untick it before submitting. */
  var rm = document.getElementById('remember_me');
  if (rm && !rm.checked && !rm.dataset.slpTouched) {
    rm.checked = true;
    rm.dataset.slpTouched = '1';
  }
  /* No onAuthStateChanged from here - page-detector.js reports the real
     state; hard-coding false overwrote a persisted signed-in session. */
}

/* ════════════════════════════════════════════════════
   CONTENT PAGES
   ════════════════════════════════════════════════════ */

/* The contact page embeds a Google map whose API key Google rejects, so
   the frame renders an error message. Offer the same place as a link
   instead; the app hands off-site links to the Maps app or the browser. */
function enhanceStatic() {
  /* Contact details: make the address and number tappable. The app hands
     mailto: and tel: to the mail and phone apps. */
  $all('#contact-table td').forEach(function (td) {
    if (td.dataset.slpLink || td.children.length) return;
    var text = td.textContent.trim();
    var href = null;
    if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text)) href = 'mailto:' + text;
    else if (/^[+()\d][\d\s()+-]{6,}$/.test(text)) href = 'tel:' + text.replace(/[^\d+]/g, '');
    if (!href) return;
    td.dataset.slpLink = '1';
    var a = el('a', null, text);
    a.href = href;
    td.textContent = '';
    td.appendChild(a);
  });

  /* Downloads is a table that is usually empty: column headings and a
     one-page pager over nothing. Say so instead. */
  var downloads = $1('table.download-items');
  if (downloads && !downloads.dataset.slpChecked) {
    downloads.dataset.slpChecked = '1';
    var body = downloads.tBodies[0];
    if (!body || !body.rows.length) {
      downloads.classList.add('slp-empty');
      downloads.parentNode.insertBefore(
        el('p', 'slp-empty-note', 'There is nothing to download at the moment.'), downloads);
    }
  }

  var frame = $1('#main-content iframe[src*="google.com/maps/embed"]');
  if (!frame || frame.dataset.slpMap) return;
  frame.dataset.slpMap = '1';
  var place = /place_id:([\w-]+)/.exec(frame.getAttribute('src') || '');
  var link = el('a', 'btn btn-default slp-map-link', 'Open in Google Maps');
  link.href = 'https://www.google.com/maps/search/?api=1&query=' +
    encodeURIComponent('Philatelic Bureau, Colombo, Sri Lanka') +
    (place ? '&query_place_id=' + encodeURIComponent(place[1]) : '');
  frame.parentNode.insertBefore(link, frame);
  frame.remove();
}

/* ════════════════════════════════════════════════════
   RENDER DIAGNOSTICS (debug builds only)
   Reports elements covering the whole viewport and the effective page
   background - surfaces "blank screen" causes in logcat.
   ════════════════════════════════════════════════════ */
slpOnce('diagnostics', function () {
  if (!window.__SLP_DIAG__) return;   // opt-in; see MainActivity.injectCombinedJs
  setTimeout(function () {
    safely('diagnostics', function () {
      var vw = window.innerWidth, vh = window.innerHeight;
      $all('body > *, body > * > *').forEach(function (e) {
        var cs = getComputedStyle(e);
        if ((cs.position === 'fixed' || cs.position === 'absolute') &&
            e.offsetWidth >= vw * 0.9 && e.offsetHeight >= vh * 0.9 &&
            cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.1) {
          log('DIAG overlay <' + e.tagName.toLowerCase() + ' id="' + e.id + '" class="' + e.className +
              '"> z=' + cs.zIndex + ' bg=' + cs.backgroundColor);
        }
      });
      var bcs = getComputedStyle(document.body);
      log('DIAG body bg=' + bcs.backgroundColor + ' opacity=' + bcs.opacity + ' display=' + bcs.display +
          ' h=' + document.body.scrollHeight + ' kids=' + document.body.children.length);
    });
  }, 900);
});

/* ──────── DISPATCH ──────── */
switch (pageType) {
  case 'HOME':           safely('home', enhanceHome); break;
  case 'PRODUCT_DETAIL': safely('product', enhanceProductDetail); break;
  case 'CART':           safely('cart', enhanceCart); break;
  case 'LOGIN':
  case 'REGISTER':       safely('auth', enhanceAuth); break;
  case 'STATIC':         safely('static', enhanceStatic); break;
}
})();
