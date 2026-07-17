/* ================================================
   SL POST STAMPS — MOBILE INJECTION SCRIPT v3
   UX enhancements + page-aware features.
   All CSS injected once; detection gates features.
   ================================================ */

'use strict';

/* ──────── INJECT ALL CSS ──────── */
var CSS = [
  '@keyframes slp-ripple{0%{transform:scale(0);opacity:.5}100%{transform:scale(4);opacity:0}}',
  '.slp-ripple-host{position:relative!important;overflow:hidden!important}',
  '.slp-ripple-dot{position:absolute;border-radius:50%;background:rgba(255,255,255,.35);width:80px;height:80px;margin:-40px 0 0 -40px;pointer-events:none;transform:scale(0);animation:slp-ripple .5s cubic-bezier(.4,0,.2,1) forwards}',
  '.slp-overlay{position:fixed;inset:0;background:rgba(0,0,0,.88);z-index:99999;display:flex;align-items:center;justify-content:center;cursor:pointer}',
  '.slp-overlay img{max-width:95vw;max-height:95vh;object-fit:contain;border-radius:4px}',
  '.slp-price-hl{display:inline-block;background:#fff3e0;color:#e65100;font-weight:700;padding:6px 14px;border-radius:8px;font-size:1.2em;border:2px solid #ff9800;margin:8px 0 !important}',
  '@keyframes slp-check{0%{opacity:0;transform:scale(.5)}50%{opacity:1}100%{opacity:0;transform:scale(1.5)}}',
  '.slp-added{position:relative}',
  '.slp-added::after{content:"\\2713";position:absolute;top:-10px;right:-10px;background:#4caf50;color:#fff;width:24px;height:24px;border-radius:50%;font-size:15px;line-height:24px;text-align:center;animation:slp-check .7s ease forwards;z-index:2}',
  '.slp-stepper{display:inline-flex;align-items:center}',
  '.slp-stepper input{width:48px!important;text-align:center;border-radius:0!important;margin:0!important;-moz-appearance:textfield}',
  '.slp-stepper input::-webkit-outer-spin-button,.slp-stepper input::-webkit-inner-spin-button{-webkit-appearance:none;margin:0}',
  '.slp-stepper button{width:32px;height:32px;border:1px solid #ccc;background:#f5f5f5;font-size:18px;line-height:1;cursor:pointer;padding:0;user-select:none}',
  '.slp-del{display:inline-block;background:#e53935;color:#fff;border:none;padding:6px 14px;font-size:13px;border-radius:4px;cursor:pointer;white-space:nowrap;margin-left:8px}',
  '.slp-focus{border-color:#1976d2!important;box-shadow:0 0 0 3px rgba(25,118,210,.2)!important;outline:none}',
  '.slp-err{border-color:#e53935!important;box-shadow:0 0 0 3px rgba(229,57,53,.2)!important}',
  '.slp-pw-wrap{position:relative;display:inline-block;width:100%}',
  '.slp-pw-wrap input{padding-right:48px!important}',
  '.slp-pw-btn{position:absolute;right:4px;top:50%;transform:translateY(-50%);background:none;border:none;color:#888;cursor:pointer;font-size:12px;padding:6px 8px;z-index:1}',
  '.slp-pw-bar{height:4px;margin-top:4px;border-radius:2px;transition:width .3s,background .3s;width:0;background:#eee}'
].join('\n');
var cssEl = document.createElement('style');
cssEl.id = 'slp-css';
cssEl.textContent = CSS;
document.head.appendChild(cssEl);

/* ──────── 1. FIX VIEWPORT ──────── */
var vp = document.querySelector('meta[name="viewport"]');
if (!vp) { vp = document.createElement('meta'); vp.name = 'viewport'; document.head.appendChild(vp); }
vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';

/* ──────── 2. PREVENT DOUBLE-TAP ZOOM ──────── */
var lastTouchEnd = 0;
document.addEventListener('touchend', function (e) {
  var now = Date.now();
  if (now - lastTouchEnd <= 300) e.preventDefault();
  lastTouchEnd = now;
}, false);

/* ──────── 3. MATERIAL RIPPLE ──────── */
var RIPPLE_SEL = ['button','.btn','a.button','input[type="submit"]','.add-to-cart','.add_to_cart_button','#login-btn','.btn-success','.btn-primary','.btn-info','.btn-default'].join(',');
document.addEventListener('click', function (e) {
  var t = e.target.closest(RIPPLE_SEL);
  if (!t) return;
  t.classList.add('slp-ripple-host');
  var d = document.createElement('span');
  d.className = 'slp-ripple-dot';
  var r = t.getBoundingClientRect();
  d.style.top = (e.clientY - r.top) + 'px';
  d.style.left = (e.clientX - r.left) + 'px';
  t.appendChild(d);
  setTimeout(function () { d.remove(); }, 550);
}, false);

/* ──────── 4. SMOOTH IMAGE LOADING ──────── */
function observeImages(root) {
  var imgs = (root || document).querySelectorAll('img:not([data-slp-obs])');
  imgs.forEach(function (img) {
    img.setAttribute('data-slp-obs', '1');
    img.style.transition = 'opacity .35s cubic-bezier(.4,0,.2,1), transform .35s cubic-bezier(.4,0,.2,1)';
    if (!img.complete || img.naturalWidth === 0) {
      img.style.opacity = '0';
      img.style.transform = 'scale(.97)';
      img.addEventListener('load', function () { img.style.opacity = '1'; img.style.transform = 'scale(1)'; });
      img.addEventListener('error', function () { img.style.opacity = '.3'; img.style.transform = 'scale(1)'; });
    }
  });
}
observeImages(document);
if (window.MutationObserver && document.body) {
  new MutationObserver(function (muts) {
    muts.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) observeImages(n); }); });
  }).observe(document.body, { childList: true, subtree: true });
}

/* ──────── 5. TOUCH FEEDBACK FOR PRODUCT CARDS ──────── */
var CARD_SEL = '.thumbnail.cart-thumb, .single-product, .product-item, .product-card';
function resetCards() { document.querySelectorAll(CARD_SEL).forEach(function (c) { c.style.transform = ''; }); }
document.addEventListener('touchstart', function (e) {
  var card = e.target.closest(CARD_SEL);
  if (card) { card.style.transition = 'transform .15s cubic-bezier(.4,0,.2,1)'; card.style.transform = 'scale(0.97)'; }
}, { passive: true });
document.addEventListener('touchend', resetCards, { passive: true });
document.addEventListener('touchcancel', resetCards, { passive: true });

/* ──────── 6. TAG AUTH PAGES ──────── */
/* Real auth routes are /login and /users/register (the server redirects
   /users/login to /login?redirect=...). */
var locPath = window.location.pathname;
if (document.body && /\/(login|register)/.test(locPath)) {
  document.body.classList.add('slp-auth-page');
}

/* ──────── 7. DISMISS POPUPS ──────── */
var POPUP_SEL = ['#cookie-notice', '#cookie-consent', '.cookie-banner', '#newsletter-popup', '.popup-overlay', '#promo-popup'];
function dismissPopups() {
  POPUP_SEL.forEach(function (s) {
    var el = document.querySelector(s);
    if (el) { el.style.transition = 'opacity .3s ease'; el.style.opacity = '0'; setTimeout(function () { el.style.display = 'none'; }, 300); }
  });
}
dismissPopups();
setTimeout(dismissPopups, 1500);

/* ──────── 7b. APP NAVIGATION HASHES ──────── */
/* #slp-browse scrolls to the product catalog (Browse tab);
   #registetModal scrolls to the register form on /login. */
function slpHandleHash() {
  var hash = location.hash;
  var target = null;
  if (hash === '#slp-browse') {
    target = document.querySelector('.nav-tabs, .features_items, .catagories-heading');
  } else if (hash.indexOf('registetModal') !== -1) {
    target = document.getElementById('registetModal');
  }
  if (target) {
    setTimeout(function () { target.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, 300);
  }
}
slpHandleHash();
if (!window.__slpHashHooked) {
  window.__slpHashHooked = true;
  window.addEventListener('hashchange', slpHandleHash);
}

/* ──────── 8. SMOOTH SCROLL ──────── */
document.addEventListener('click', function (e) {
  var a = e.target.closest('a[href^="#"]');
  if (!a) return;
  var id = a.getAttribute('href');
  if (!id || id === '#') return;
  var t = document.querySelector(id);
  if (t) { e.preventDefault(); t.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
}, false);

/* ──────── 9. STAGGER ANIMATION ──────── */
function staggerCards() {
  var cards = document.querySelectorAll('.thumbnail.cart-thumb');
  cards.forEach(function (card, i) {
    if (card.dataset.slpStaggered) return;
    card.dataset.slpStaggered = '1';
    card.style.opacity = '0';
    card.style.transform = 'translateY(16px)';
    card.style.transition = 'opacity .4s cubic-bezier(.4,0,.2,1), transform .4s cubic-bezier(.4,0,.2,1)';
    card.style.transitionDelay = Math.min(i * 50, 400) + 'ms';
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { card.style.opacity = '1'; card.style.transform = 'translateY(0)'; });
    });
  });
}
requestAnimationFrame(function () { staggerCards(); });

/* ════════════════════════════════════════════════════
   10. PAGE DETECTION + BRIDGE HELPERS
   ════════════════════════════════════════════════════ */

function detectPage() {
  var p = window.location.pathname;
  if (/\/product\/\d+/i.test(p) || document.querySelector('.product-view')) return 'PRODUCT_DETAIL';
  if (/\/cart-view|\/orders\//i.test(p)) return 'CART';
  if (/\/checkout|\/add-to-cart/i.test(p)) return 'CHECKOUT';
  if (/\/register/i.test(p)) return 'REGISTER';
  if (/\/login/i.test(p)) return 'LOGIN';
  return 'UNKNOWN';
}

var pageType = window.__SLP_PAGE_TYPE__ || detectPage();
var br = window.SLPBridge;

function brCall(m, a, b, c) {
  // Shares the flood guard with page-detector.js (same window counter)
  window.__slpBridgeCalls = (window.__slpBridgeCalls || 0) + 1;
  if (window.__slpBridgeCalls === 300 && br && br.log) {
    try { br.log('BRIDGE FLOOD (' + m + '): ' + (new Error().stack || 'no stack')); } catch (e) {}
  }
  if (window.__slpBridgeCalls > 400) return;
  if (br && typeof br[m] === 'function') { try { br[m](a, b, c); } catch (ignored) {} }
}

/* ════════════════════════════════════════════════════
   11. PAGE-SPECIFIC ENHANCEMENTS
   ════════════════════════════════════════════════════ */

/* ──── SHARED HELPERS ──── */

function autoFocusFirst() {
  var inputs = document.querySelectorAll('input[type="text"], input[type="email"], input[type="password"], input[type="tel"]');
  for (var i = 0; i < inputs.length; i++) { if (!inputs[i].value) { inputs[i].focus(); break; } }
}

function addPwToggles() {
  document.querySelectorAll('input[type="password"]').forEach(function (pw) {
    if (pw.dataset.slpPw) return;
    pw.dataset.slpPw = '1';
    var wrap = document.createElement('span');
    wrap.className = 'slp-pw-wrap';
    pw.parentNode.insertBefore(wrap, pw);
    wrap.appendChild(pw);
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'slp-pw-btn';
    btn.textContent = 'Show';
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      var isPass = pw.type === 'password';
      pw.type = isPass ? 'text' : 'password';
      btn.textContent = isPass ? 'Hide' : 'Show';
    });
    wrap.appendChild(btn);
  });
}

/* ──── PRODUCT_DETAIL ──── */

function enhanceProductDetail() {
  /* Tap-to-zoom on main product image (.product-view holds the hero image) */
  var prodImg = document.querySelector('.product-view img, .product-image img, .main-image img, .product-img img');
  if (prodImg) {
    prodImg.style.cursor = 'zoom-in';
    prodImg.addEventListener('click', function () {
      var overlay = document.createElement('div');
      overlay.className = 'slp-overlay';
      var bigImg = document.createElement('img');
      bigImg.src = prodImg.src;
      overlay.appendChild(bigImg);
      overlay.addEventListener('click', function () { overlay.remove(); });
      document.body.appendChild(overlay);
    });
  }

  /* Price highlight */
  var priceEl = document.querySelector('.item-price, .product-price, .price');
  if (priceEl && !priceEl.classList.contains('slp-price-hl')) { priceEl.classList.add('slp-price-hl'); }

  /* Animated add-to-cart */
  var atc = document.querySelector('.product .add-to-cart, button.add-to-cart, .btn-add-to-cart');
  if (atc) {
    atc.style.transition = 'transform .2s cubic-bezier(.4,0,.2,1)';
    atc.addEventListener('click', function () {
      atc.style.transform = 'scale(.94)';
      setTimeout(function () {
        atc.style.transform = 'scale(1)';
        atc.classList.add('slp-added');
        setTimeout(function () { atc.classList.remove('slp-added'); }, 750);
      }, 150);
    });
  }

  /* Bridge */
  var titleEl = document.querySelector('.product h2, .product_title, h1, .product-name');
  brCall('onProductViewed',
    (titleEl ? titleEl.textContent : '').trim(),
    (priceEl ? priceEl.textContent : '').trim(),
    (prodImg ? prodImg.src : '')
  );
}

/* ──── CART ──── */

function updateCartCount() {
  var items = document.querySelectorAll('table tbody tr, .cart_item, .cart-item');
  var n = items.length || 0;
  if (window.__slpCartCount === n) return;
  window.__slpCartCount = n;
  brCall('onCartUpdated', n);
}

function enhanceCart() {
  /* Quantity steppers (+/- buttons) */
  document.querySelectorAll('.quantity input, input.qty, input[type="number"][name*="qty"], input[type="number"][name*="quantity"]').forEach(function (input) {
    if (input.dataset.slpStep) return;
    input.dataset.slpStep = '1';
    var wrap = document.createElement('span');
    wrap.className = 'slp-stepper';
    input.parentNode.insertBefore(wrap, input);
    wrap.appendChild(input);
    var dec = document.createElement('button');
    dec.type = 'button'; dec.textContent = '−';
    var inc = document.createElement('button');
    inc.type = 'button'; inc.textContent = '+';
    wrap.insertBefore(dec, input);
    wrap.appendChild(inc);
    var min = parseInt(input.min, 10) || 1;
    var max = parseInt(input.max, 10) || 999;
    function change(by) {
      var v = parseInt(input.value, 10) || min;
      v += by;
      if (v < min) v = min;
      if (v > max) v = max;
      input.value = v;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
    dec.addEventListener('click', function () { change(-1); });
    inc.addEventListener('click', function () { change(1); });
  });

  /* Swipe-to-delete on cart rows */
  var sx = 0, sy = 0;
  document.addEventListener('touchstart', function (e) { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    var row = e.target.closest('tr.cart_item, tr');
    if (!row || row.querySelector('.slp-del')) return;
    var dx = e.changedTouches[0].clientX - sx;
    var dy = e.changedTouches[0].clientY - sy;
    if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(dy) * 2) {
      var del = document.createElement('button');
      del.className = 'slp-del';
      del.textContent = 'Remove';
      del.addEventListener('click', function () {
        var rm = row.querySelector('.remove a, a.remove');
        if (rm) { rm.click(); } else { row.remove(); }
        updateCartCount();
      });
      row.appendChild(del);
      setTimeout(function () { if (del.parentNode) del.remove(); }, 4000);
    }
  }, { passive: true });

  /* Prominent cart total at top */
  var totalEl = document.querySelector('.cart-total .amount, .order-total .amount, .cart-subtotal .amount, .woocommerce-Price-amount.amount, .total .amount');
  if (totalEl && !document.querySelector('.slp-cart-banner')) {
    var banner = document.createElement('div');
    banner.className = 'slp-cart-banner';
    banner.style.cssText = 'background:#fff3e0;padding:10px 16px;border-radius:8px;margin-bottom:16px;font-size:1.1em;text-align:center;border:1px solid #ffe0b2;font-weight:700;color:#e65100';
    banner.textContent = 'Cart Total: ' + (totalEl.textContent || '').trim();
    var container = document.querySelector('.woocommerce, .cart-page, .main-content, main, .content');
    if (container) container.insertBefore(banner, container.firstChild);
  }

  updateCartCount();
}

/* ──── CHECKOUT ──── */

function enhanceCheckout() {
  /* Visual focus indicators on form fields */
  document.querySelectorAll('input:not([type="submit"]):not([type="button"]), select, textarea').forEach(function (f) {
    f.addEventListener('focus', function () { f.classList.add('slp-focus'); });
    f.addEventListener('blur', function () { f.classList.remove('slp-focus'); });
  });

  /* Required-field validation on first submit */
  var form = document.querySelector('.woocommerce-checkout form, form.checkout, form');
  if (form) {
    form.addEventListener('submit', function (e) {
      var ok = true;
      form.querySelectorAll('[required], [aria-required="true"]').forEach(function (f) {
        f.classList.remove('slp-err');
        if (f.type === 'checkbox' || f.type === 'radio') return;
        if (!f.value || !f.value.trim()) { f.classList.add('slp-err'); ok = false; }
      });
      if (!ok) { e.preventDefault(); e.stopPropagation(); }
    });
  }
}

/* ──── LOGIN ──── */

/* The site's only sign-in form (.home-login) lives inside the navbar, which
   the app hides on every page. Move it into the visible content area, in
   front of the inline register block (#registetModal), and wrap it as a card. */
function relocateLoginForm() {
  var reg = document.getElementById('registetModal');
  if (!document.querySelector('.slp-login-card')) {
    var home = document.querySelector('.navbar .home-login, nav .home-login');
    if (home && reg && reg.parentNode) {
      var card = document.createElement('div');
      card.className = 'slp-login-card';
      var h = document.createElement('h2');
      h.textContent = 'Sign In';
      card.appendChild(h);
      card.appendChild(home);
      reg.parentNode.insertBefore(card, reg);
    }
  }
  if (location.hash.indexOf('registetModal') !== -1 && reg) {
    setTimeout(function () { reg.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, 200);
  }
  /* Pre-tick "Remember me" so the site keeps the session alive longer;
     the user can still untick it before submitting. */
  var rm = document.getElementById('remember_me');
  if (rm && !rm.checked && !rm.dataset.slpTouched) {
    rm.checked = true;
    rm.dataset.slpTouched = '1';
  }
}

function enhanceLogin() {
  relocateLoginForm();
  autoFocusFirst();
  addPwToggles();
  /* NOTE: no onAuthStateChanged here — page-detector.js reports the real
     state; hard-coding false overwrote a persisted logged-in session. */
}

/* ──── REGISTER ──── */

function enhanceRegister() {
  relocateLoginForm();
  autoFocusFirst();
  addPwToggles();

  /* Real-time password strength indicator */
  document.querySelectorAll('input[type="password"]').forEach(function (pw) {
    if (pw.dataset.slpStr) return;
    pw.dataset.slpStr = '1';
    var bar = document.createElement('div');
    bar.className = 'slp-pw-bar';
    pw.parentNode.appendChild(bar);
    pw.addEventListener('input', function () {
      var v = pw.value;
      var s = 0;
      if (v.length >= 6) s++;
      if (v.length >= 10) s++;
      if (/[A-Z]/.test(v)) s++;
      if (/[0-9]/.test(v)) s++;
      if (/[^A-Za-z0-9]/.test(v)) s++;
      var colors = ['#eee', '#e53935', '#ff9800', '#ff9800', '#4caf50', '#388e3c'];
      bar.style.width = s > 0 ? (s * 20) + '%' : '0';
      bar.style.background = colors[s] || '#388e3c';
      bar.title = ['', 'Weak', 'Fair', 'Fair', 'Strong', 'Very strong'][s] || '';
    });
  });
}

/* ──────── RENDER DIAGNOSTICS ──────── */
/* Reports elements that cover the whole viewport plus the effective page
   background — surfaces "black screen" causes in logcat. */
setTimeout(function () {
  try {
    var vw = window.innerWidth, vh = window.innerHeight;
    var els = document.querySelectorAll('body > *, body > * > *');
    for (var i = 0; i < els.length; i++) {
      var el = els[i], cs = getComputedStyle(el);
      if ((cs.position === 'fixed' || cs.position === 'absolute') &&
          el.offsetWidth >= vw * 0.9 && el.offsetHeight >= vh * 0.9 &&
          cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.1) {
        brCall('log', 'DIAG overlay <' + el.tagName.toLowerCase() + ' id="' + el.id + '" class="' + el.className + '"> z=' + cs.zIndex + ' bg=' + cs.backgroundColor);
      }
    }
    var bcs = getComputedStyle(document.body);
    brCall('log', 'DIAG body bg=' + bcs.backgroundColor + ' opacity=' + bcs.opacity +
      ' display=' + bcs.display + ' h=' + document.body.scrollHeight +
      ' kids=' + document.body.children.length + ' anim=' + bcs.animationName);
  } catch (e) { brCall('log', 'DIAG failed: ' + e.message); }
}, 900);

/* ──── DISPATCH BASED ON PAGE TYPE ──── */
switch (pageType) {
  case 'PRODUCT_DETAIL': enhanceProductDetail(); break;
  case 'CART': enhanceCart(); break;
  case 'CHECKOUT': enhanceCheckout(); break;
  case 'LOGIN': enhanceLogin(); break;
  case 'REGISTER': enhanceRegister(); break;
}
