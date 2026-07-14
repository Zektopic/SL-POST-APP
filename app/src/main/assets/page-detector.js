(function () {
    'use strict';

    var PAGE_TYPES = {
        HOME: 'home', PRODUCT_LISTING: 'product-listing', PRODUCT_DETAIL: 'product-detail',
        CART: 'cart', CHECKOUT: 'checkout', LOGIN: 'login', REGISTER: 'register',
        ACCOUNT: 'account', STATIC: 'static', UNKNOWN: 'unknown'
    };

    function callBridge(method, args) {
        // Flood guard: capture one stack trace, then stop hammering the
        // native bridge if something calls it in a loop.
        window.__slpBridgeCalls = (window.__slpBridgeCalls || 0) + 1;
        if (window.__slpBridgeCalls === 300 && window.SLPBridge) {
            try { window.SLPBridge.log('BRIDGE FLOOD (' + method + '): ' + (new Error().stack || 'no stack')); } catch (e) {}
        }
        if (window.__slpBridgeCalls > 400) return;
        if (window.SLPBridge && typeof window.SLPBridge[method] === 'function') {
            try { window.SLPBridge[method].apply(window.SLPBridge, args); }
            catch (e) { console.log('[SLP PageDetector] Bridge call failed:', method, e.message); }
        }
    }

    // ── Tier 1: URL-based rules (fast path, highest confidence) ──────────────

    // Real routes on stamps.slpost.gov.lk (CakePHP app):
    //   /product/6577       product detail (singular!)
    //   /cart-view          shopping cart
    //   /login              login (server redirects /users/login here)
    //   /users/register     registration
    //   /users/view|edit    account
    function detectFromURL() {
        var path = window.location.pathname.toLowerCase();
        if (path === '/' || path === '') return 'HOME';
        if (/\/product\/\d+/i.test(path)) return 'PRODUCT_DETAIL';
        if (/\/products?\/(view|category)/i.test(path)) return 'PRODUCT_DETAIL';
        if (/\/cart-view|\/orders\//i.test(path)) return 'CART';
        if (/\/checkout|\/add-to-cart/i.test(path)) return 'CHECKOUT';
        if (/\/login/i.test(path)) {
            // Registration is an inline block on /login, addressed by hash
            return (window.location.hash || '').indexOf('registetModal') !== -1 ? 'REGISTER' : 'LOGIN';
        }
        if (/\/register/i.test(path)) return 'REGISTER';
        if (/\/users\/(view|edit|password)/i.test(path)) return 'ACCOUNT';
        if (/\/(contact-us|downloads|general-infor|terms-and-conditions|payment-methods|how-to-)/i.test(path)) return 'STATIC';
        return null;
    }

    // ── Tier 2: DOM-based fallbacks (medium confidence) ──────────────────────

    function detectFromDOM() {
        // .product-view is unique to the detail page; .single-product is the
        // card class used on every listing grid (dozens per page).
        if (document.querySelector('.product-view')) return 'PRODUCT_DETAIL';

        var items = document.querySelectorAll('.single-product, .product-item, .product-card');
        if (items.length > 1) return 'PRODUCT_LISTING';

        var cartTable = document.querySelector('table');
        if (cartTable) {
            var text = (cartTable.textContent || '').toLowerCase();
            if (/product|item|quantity|price|total|subtotal/.test(text) && /cart|basket/.test(text)) {
                return 'CART';
            }
        }
        if (document.querySelector('.cart-items, .cart-contents, .shopping-cart')) return 'CART';

        if (document.querySelector('[name*="billing"], [name*="shipping"], [id*="billing"], [id*="shipping"]')) {
            return 'CHECKOUT';
        }

        var pwField = document.querySelector('input[type="password"]');
        if (pwField && !document.querySelector('input[name*="confirm"], input[name*="password2"]')) {
            var bodyText = (document.body.textContent || '').toLowerCase();
            if (/login|sign in|log in/.test(bodyText)) return 'LOGIN';
        }

        if (pwField && document.querySelector('input[name*="confirm"], input[name*="password2"]')) {
            return 'REGISTER';
        }
        if (pwField && document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"]').length > 4) {
            if (/(register|sign up|create account)/i.test((document.body.textContent || ''))) return 'REGISTER';
        }

        if (/(order history|my orders|my account|profile|account details)/i.test((document.body.textContent || ''))) {
            return 'ACCOUNT';
        }

        return 'UNKNOWN';
    }

    // ── Context extraction helpers ───────────────────────────────────────────

    function trimText(el, max) {
        if (!el) return '';
        var t = (el.textContent || '').trim();
        return t.length > max ? t.substring(0, max) : t;
    }

    function extractProductDetail() {
        var title = trimText(document.querySelector('h1, .product h2, .product-title, .product-name') || document.querySelector('title'), 200);
        var price = trimText(document.querySelector('.item-price, .price, strong, b'), 50);
        var area = document.querySelector('.product-view, .product-detail, .single-product, article');
        var img = area
            ? area.querySelector('img[src]')
            : document.querySelector('.product-image img, .main-image img, figure img');
        var imageUrl = img ? img.getAttribute('src') : '';
        console.log('[SLP PageDetector] Product:', { title: title, price: price, imageUrl: imageUrl });
        callBridge('onProductViewed', [title, price, imageUrl]);
    }

    function extractCartCount() {
        var rows = document.querySelectorAll('table tbody tr, tr.cart_item, .cart-item, .cart-row');
        var count = rows.length || 0;
        if (window.__slpCartCount === count) return;
        window.__slpCartCount = count;
        console.log('[SLP PageDetector] Cart items:', count);
        callBridge('onCartUpdated', [count]);
    }

    function extractAuthState() {
        var logout = document.querySelector('a[href*="logout"], a[href*="sign-out"]');
        var profile = document.querySelector('a[href*="account"], a[href*="profile"], a[href*="users/view"]');
        var isLoggedIn = !!(logout || profile);
        var accountUrl = profile ? profile.getAttribute('href') : '';
        console.log('[SLP PageDetector] Auth:', { isLoggedIn: isLoggedIn, accountUrl: accountUrl });
        callBridge('onAuthStateChanged', [isLoggedIn, accountUrl]);
    }

    function extractCartBadge() {
        var badge = document.querySelector('.cart-count, .cart-badge, .badge, .items-count, .cart-qty');
        if (badge) {
            var count = parseInt((badge.textContent || '0').replace(/\D/g, ''), 10) || 0;
            if (window.__slpCartCount === count) return;
            window.__slpCartCount = count;
            console.log('[SLP PageDetector] Cart badge:', count);
            callBridge('onCartUpdated', [count]);
        }
    }

    // ── Main ─────────────────────────────────────────────────────────────────

    function run() {
        var typeEnum = detectFromURL();
        var confidence = 'url';

        if (!typeEnum) {
            typeEnum = detectFromDOM();
            confidence = 'dom';
        }

        var cssClass = 'slp-page-' + PAGE_TYPES[typeEnum];

        // Tag body
        if (document.body) {
            document.body.classList.add(cssClass);
        } else {
            document.addEventListener('DOMContentLoaded', function () {
                document.body.classList.add(cssClass);
            });
        }

        // Store on window
        window.__SLP_PAGE_TYPE__ = typeEnum;
        window.__SLP_PAGE_CONFIDENCE__ = confidence;

        console.log('[SLP PageDetector] Page: ' + typeEnum + ' (confidence: ' + confidence + ')');

        // Notify native bridge with stringify
        callBridge('onPageDetected', [
            typeEnum,
            JSON.stringify({ url: window.location.href, title: document.title, confidence: confidence })
        ]);

        // Context extraction per page type
        switch (typeEnum) {
            case 'PRODUCT_DETAIL': extractProductDetail(); break;
            case 'CART': extractCartCount(); break;
            case 'LOGIN':
            case 'REGISTER':
            case 'ACCOUNT': extractAuthState(); break;
        }

        // Always scan for cart badge
        extractCartBadge();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', run);
    } else {
        run();
    }
})();
