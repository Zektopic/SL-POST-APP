/* ================================================
   SL POST STAMPS — MOBILE INJECTION SCRIPT v2
   Smooth UX enhancements for the Bootstrap 3 site.
   ================================================ */

(function () {
  'use strict';

  /* ──────── 1. FIX VIEWPORT ──────── */
  var viewport = document.querySelector('meta[name="viewport"]');
  if (!viewport) {
    viewport = document.createElement('meta');
    viewport.name = 'viewport';
    document.head.appendChild(viewport);
  }
  viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';

  /* ──────── 2. PREVENT DOUBLE-TAP ZOOM ──────── */
  var lastTouchEnd = 0;
  document.addEventListener('touchend', function (e) {
    var now = Date.now();
    if (now - lastTouchEnd <= 300) {
      e.preventDefault();
    }
    lastTouchEnd = now;
  }, false);

  /* ──────── 3. MATERIAL RIPPLE ON INTERACTIVE ELEMENTS ──────── */
  var rippleCSS = [
    '@keyframes slp-ripple {',
    '  0%   { transform: scale(0); opacity: .5; }',
    '  100% { transform: scale(4); opacity: 0;  }',
    '}',
    '.slp-ripple-host { position: relative !important; overflow: hidden !important; }',
    '.slp-ripple-dot {',
    '  position: absolute; border-radius: 50%;',
    '  background: rgba(255,255,255,.35);',
    '  width: 80px; height: 80px;',
    '  margin-top: -40px; margin-left: -40px;',
    '  pointer-events: none; transform: scale(0);',
    '  animation: slp-ripple .5s cubic-bezier(.4,0,.2,1) forwards;',
    '}'
  ].join('\n');

  var rippleStyle = document.createElement('style');
  rippleStyle.id = 'slp-ripple-style';
  rippleStyle.textContent = rippleCSS;
  document.head.appendChild(rippleStyle);

  /* Ripple targets: buttons and primary interactive elements */
  var RIPPLE_SELECTORS = [
    'button', '.btn', 'a.button', 'input[type="submit"]',
    '.add-to-cart', '.add_to_cart_button', '#login-btn',
    '.btn-success', '.btn-primary', '.btn-info', '.btn-default'
  ].join(', ');

  document.addEventListener('click', function (e) {
    var target = e.target.closest(RIPPLE_SELECTORS);
    if (!target) return;
    target.classList.add('slp-ripple-host');
    var dot = document.createElement('span');
    dot.className = 'slp-ripple-dot';
    var rect = target.getBoundingClientRect();
    dot.style.top  = (e.clientY - rect.top)  + 'px';
    dot.style.left = (e.clientX - rect.left) + 'px';
    target.appendChild(dot);
    setTimeout(function () { dot.remove(); }, 550);
  }, false);

  /* ──────── 4. SMOOTH IMAGE LOADING ──────── */
  /* Images transition from invisible to visible once loaded */
  function observeImages(root) {
    var imgs = (root || document).querySelectorAll('img:not([data-slp-obs])');
    imgs.forEach(function (img) {
      img.setAttribute('data-slp-obs', '1');
      img.style.transition = 'opacity .35s cubic-bezier(.4,0,.2,1), transform .35s cubic-bezier(.4,0,.2,1)';
      if (!img.complete || img.naturalWidth === 0) {
        img.style.opacity = '0';
        img.style.transform = 'scale(.97)';
        img.addEventListener('load', function () {
          img.style.opacity = '1';
          img.style.transform = 'scale(1)';
        });
        img.addEventListener('error', function () {
          img.style.opacity = '.3';
          img.style.transform = 'scale(1)';
        });
      }
    });
  }
  observeImages(document);

  /* Watch for dynamically-injected images (lazy loading, AJAX) */
  if (window.MutationObserver) {
    new MutationObserver(function (mutations) {
      mutations.forEach(function (m) {
        m.addedNodes.forEach(function (node) {
          if (node.nodeType === 1) observeImages(node);
        });
      });
    }).observe(document.body, { childList: true, subtree: true });
  }

  /* ──────── 5. TOUCH FEEDBACK FOR PRODUCT CARDS ──────── */
  var CARD_SELECTORS = '.product-item, .product-card, ul.products li.product, .col-sm-3 > div';

  document.addEventListener('touchstart', function (e) {
    var card = e.target.closest(CARD_SELECTORS);
    if (card) {
      card.style.transition = 'transform .15s cubic-bezier(.4,0,.2,1)';
      card.style.transform = 'scale(0.97)';
    }
  }, { passive: true });

  document.addEventListener('touchend', function () {
    document.querySelectorAll(CARD_SELECTORS).forEach(function (c) {
      c.style.transform = '';
    });
  }, { passive: true });

  document.addEventListener('touchcancel', function () {
    document.querySelectorAll(CARD_SELECTORS).forEach(function (c) {
      c.style.transform = '';
    });
  }, { passive: true });

  /* ──────── 6. TAG AUTH PAGES ──────── */
  var path = window.location.pathname;
  if (path.indexOf('/users/login') !== -1 || path.indexOf('/users/register') !== -1) {
    document.body.classList.add('slp-auth-page');
  }

  /* ──────── 7. DISMISS POPUPS / COOKIE BANNERS ──────── */
  var POPUP_SELECTORS = [
    '#cookie-notice', '#cookie-consent', '.cookie-banner',
    '#newsletter-popup', '.popup-overlay', '#promo-popup'
  ];

  function dismissPopups() {
    POPUP_SELECTORS.forEach(function (sel) {
      var el = document.querySelector(sel);
      if (el) {
        el.style.transition = 'opacity .3s ease';
        el.style.opacity = '0';
        setTimeout(function () { el.style.display = 'none'; }, 300);
      }
    });
  }
  dismissPopups();
  setTimeout(dismissPopups, 1500);

  /* ──────── 8. SMOOTH SCROLL FOR ALL ANCHOR LINKS ──────── */
  document.addEventListener('click', function (e) {
    var anchor = e.target.closest('a[href^="#"]');
    if (!anchor) return;
    var targetId = anchor.getAttribute('href');
    if (!targetId || targetId === '#') return;
    var target = document.querySelector(targetId);
    if (target) {
      e.preventDefault();
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, false);

  /* ──────── 9. STAGGER ANIMATION FOR PRODUCT CARDS ON LOAD ──────── */
  /* Cards gently animate in on first page load for a premium feel */
  function staggerCards() {
    var cards = document.querySelectorAll('.col-sm-3 > div, .col-sm-3 > a');
    cards.forEach(function (card, i) {
      if (card.dataset.slpStaggered) return;
      card.dataset.slpStaggered = '1';
      card.style.opacity = '0';
      card.style.transform = 'translateY(16px)';
      card.style.transition = 'opacity .4s cubic-bezier(.4,0,.2,1), transform .4s cubic-bezier(.4,0,.2,1)';
      card.style.transitionDelay = Math.min(i * 50, 400) + 'ms';
      /* Force reflow then animate in */
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          card.style.opacity = '1';
          card.style.transform = 'translateY(0)';
        });
      });
    });
  }

  /* Run after a frame so CSS is already applied */
  requestAnimationFrame(function () {
    staggerCards();
  });

})();
