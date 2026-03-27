/* ================================================
   SL POST STAMPS — MOBILE INJECTION SCRIPT
   UX enhancements injected on every page load.
   ================================================ */

(function () {
    'use strict';

    /* -------- 1. FIX VIEWPORT -------- */
    /* Ensure no unwanted zooming or extra width on mobile */
    var viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
        viewport = document.createElement('meta');
        viewport.name = 'viewport';
        document.head.appendChild(viewport);
    }
    viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';

    /* -------- 2. PREVENT DOUBLE-TAP ZOOM -------- */
    var lastTouchEnd = 0;
    document.addEventListener('touchend', function (e) {
        var now = Date.now();
        if (now - lastTouchEnd <= 300) {
            e.preventDefault();
        }
        lastTouchEnd = now;
    }, false);

    /* -------- 3. RIPPLE EFFECT ON BUTTONS & LINKS -------- */
    /* Injects Material Design-style ripple on tap */
    var rippleStyleAdded = false;
    function addRippleStyle() {
        if (rippleStyleAdded) return;
        rippleStyleAdded = true;
        var s = document.createElement('style');
        s.textContent = [
            '@keyframes slp-ripple {',
            '  from { transform: scale(0); opacity: 0.6; }',
            '  to   { transform: scale(4); opacity: 0; }',
            '}',
            '.slp-ripple-host { position: relative !important; overflow: hidden !important; }',
            '.slp-ripple-dot {',
            '  position: absolute; border-radius: 50%;',
            '  background: rgba(255,255,255,0.38);',
            '  width: 100px; height: 100px;',
            '  margin-top: -50px; margin-left: -50px;',
            '  pointer-events: none; transform: scale(0);',
            '  animation: slp-ripple 0.55s ease-out forwards;',
            '}'
        ].join('');
        document.head.appendChild(s);
    }

    document.addEventListener('click', function (e) {
        var target = e.target.closest(
            'button, .btn, a.button, input[type="submit"], .add_to_cart_button, #login-btn'
        );
        if (!target) return;
        addRippleStyle();
        target.classList.add('slp-ripple-host');
        var dot = document.createElement('span');
        dot.className = 'slp-ripple-dot';
        var rect = target.getBoundingClientRect();
        dot.style.top  = (e.clientY - rect.top)  + 'px';
        dot.style.left = (e.clientX - rect.left) + 'px';
        target.appendChild(dot);
        setTimeout(function () { dot.remove(); }, 600);
    }, false);

    /* -------- 4. SMOOTH IMAGE FADE-IN -------- */
    /* Images fade in once loaded instead of popping in abruptly */
    function fadeInImages(root) {
        (root || document).querySelectorAll('img').forEach(function (img) {
            if (img.dataset.slpFade) return;   // already processed
            img.dataset.slpFade = '1';
            img.style.transition = 'opacity 0.35s ease';
            if (!img.complete || img.naturalWidth === 0) {
                img.style.opacity = '0';
                img.addEventListener('load', function () { img.style.opacity = '1'; });
                img.addEventListener('error', function () { img.style.opacity = '0.3'; });
            }
        });
    }
    fadeInImages(document);

    /* Watch for dynamically injected images (lazy-loaded products) */
    if (window.MutationObserver) {
        new MutationObserver(function (mutations) {
            mutations.forEach(function (m) {
                m.addedNodes.forEach(function (node) {
                    if (node.nodeType === 1) fadeInImages(node);
                });
            });
        }).observe(document.body, { childList: true, subtree: true });
    }

    /* -------- 5. ACTIVE STATE FEEDBACK FOR PRODUCT CARDS -------- */
    /* CSS :active doesn't always fire fast enough on mobile WebView */
    document.addEventListener('touchstart', function (e) {
        var card = e.target.closest('.product-item, .product-card, ul.products li.product');
        if (card) card.style.transform = 'scale(0.97)';
    }, { passive: true });

    document.addEventListener('touchend', function (e) {
        document.querySelectorAll('.product-item, .product-card, ul.products li.product').forEach(function (c) {
            c.style.transform = '';
        });
    }, { passive: true });

    /* -------- 6. HIDE NATIVE SITE COOKIE/POPUP BANNERS -------- */
    /* Common cookie consent or promo popup IDs — adjust as needed */
    var POPUP_SELECTORS = [
        '#cookie-notice', '#cookie-consent', '.cookie-banner',
        '#newsletter-popup', '.popup-overlay', '#promo-popup'
    ];
    function dismissPopups() {
        POPUP_SELECTORS.forEach(function (sel) {
            var el = document.querySelector(sel);
            if (el) el.style.display = 'none';
        });
    }
    dismissPopups();
    setTimeout(dismissPopups, 1500); // catch delayed-render popups

})();
