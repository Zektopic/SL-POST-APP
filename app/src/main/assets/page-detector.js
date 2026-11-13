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
    //   /users/register     registration (POST only; the form is on /login)
    //   /users/password     forgot password
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
        // /users/password is the signed-out "Forgot password" form, not an
        // account page. Classify it with the sign-in flow.
        if (/\/users\/password/i.test(path)) return 'LOGIN';
        if (/\/users\/(view|edit|userEdit)/i.test(path)) return 'ACCOUNT';
        if (/\/(contact-us|downloads|general-infor|postage-infor|terms-and-conditions|payment-methods|how-to-)/i.test(path)) return 'STATIC';
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

    /**
     * The only onProductViewed reporter. inject.js used to send a second one
     * built from different selectors, so the toolbar title depended on which
     * call landed last.
     *
     * Real markup: the heading is `<h2><i>Buy</i> NAME</h2>` and the price is
     * `.item-price`. The old price selector fell through to the first
     * `strong, b` on the page, which is whatever bold text came first.
     */
    function extractProductDetail() {
        var area = document.querySelector('.product-view') || document;
        var heading = area.querySelector('h2, h1') || document.querySelector('h1, h2');
        var title = trimText(heading, 200).replace(/^buy\s+/i, '').replace(/\s+/g, ' ');
        var price = trimText(document.querySelector('.item-price'), 50);
        var img = area.querySelector('img[src]');
        var imageUrl = img ? (img.src || '') : '';
        var prev = window.__slpProduct;
        if (prev && prev.url === location.href && prev.title === title) return;
        window.__slpProduct = { url: location.href, title: title };
        callBridge('onProductViewed', [title, price, imageUrl]);
    }

    // ── Cart count ───────────────────────────────────────────────────────────

    /**
     * The cart badge comes from /updateCartCount, the endpoint the site's own
     * "My cart (n - Rs. x)" button is filled from. It answers
     * {"count": <distinct items>, "total": "<amount>"} for guests and
     * signed-in users alike.
     *
     * This replaces two DOM scrapers that were both wrong:
     *
     *  - a `table tbody tr` row counter. On /cart-view that also counted the
     *    nested per-item detail table and the sub-total / postage / grand-total
     *    rows, so one item reported 9 and an EMPTY cart reported 2;
     *  - a `.badge` text parser. The only `.badge` on the site is the "+" on
     *    the search accordion, so every other page reset the count to 0 -
     *    including straight after adding an item.
     */
    function reportCartCount(count) {
        if (window.__slpCartCount === count) return;
        window.__slpCartCount = count;
        callBridge('onCartUpdated', [count]);
    }

    function parseCartCount(text) {
        try {
            var n = parseInt(JSON.parse(text).count, 10);
            if (!isNaN(n) && n >= 0) reportCartCount(n);
        } catch (e) { /* not JSON: signed-out redirect or an error page */ }
    }

    function fetchCartCount() {
        if (window.__slpCartFetching) return;
        window.__slpCartFetching = true;
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', '/updateCartCount', true);
            xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
            xhr.onload = function () {
                window.__slpCartFetching = false;
                if (xhr.status === 200) parseCartCount(xhr.responseText);
            };
            xhr.onerror = xhr.ontimeout = function () { window.__slpCartFetching = false; };
            xhr.timeout = 15000;
            xhr.send();
        } catch (e) { window.__slpCartFetching = false; }
    }

    /**
     * Keeps the badge live without a page load: the site adds, updates and
     * removes cart lines over jQuery AJAX. Reuse the count when the site
     * fetches it itself, and re-fetch after any other cart mutation.
     */
    function hookCartAjax() {
        if (window.__slpCartHooked) return;
        var $ = window.jQuery;
        if (!$ || !$.fn || typeof $(document).ajaxSuccess !== 'function') return;
        window.__slpCartHooked = true;
        $(document).ajaxSuccess(function (event, xhr, settings) {
            var url = (settings && settings.url) || '';
            if (/updateCartCount/i.test(url)) {
                parseCartCount(xhr.responseText);
            } else if (/add-to-cart|update-cart|clear-cart|remove-?from-?cart/i.test(url)) {
                fetchCartCount();
            }
        });
    }

    function syncCartCount() {
        hookCartAjax();
        // Once per document. This file runs at first paint, again at page
        // finish, and again on every fragment navigation.
        if (window.__slpCartSynced) return;
        window.__slpCartSynced = true;
        fetchCartCount();
    }

    function extractAuthState() {
        var logout = document.querySelector('a[href*="logout"], a[href*="sign-out"]');
        var profile = document.querySelector('a[href*="account"], a[href*="profile"], a[href*="users/view"]');
        var isLoggedIn = !!(logout || profile);
        // Use the .href DOM property, not getAttribute('href'): the attribute is
        // usually relative on this site (/users/view/123), and WebView.loadUrl()
        // does not resolve a relative path against the current page.
        var accountUrl = profile ? (profile.href || '') : '';
        // Report changes only. This runs on every injection (three or more
        // per page), and each bridge call is a preferences write plus a hop to
        // the UI thread on the native side.
        var key = isLoggedIn + '|' + accountUrl;
        if (window.__slpAuth === key) return;
        window.__slpAuth = key;
        callBridge('onAuthStateChanged', [isLoggedIn, accountUrl]);
    }

    // ── Main ─────────────────────────────────────────────────────────────────

    function run() {
        var typeEnum = detectFromURL();
        var confidence = 'url';

        if (!typeEnum) {
            typeEnum = detectFromDOM();
            confidence = 'dom';
        }

        var cssClass = 'slp-page-' + (PAGE_TYPES[typeEnum] || 'unknown');

        // Tag body. Replace any previous slp-page-* class rather than adding
        // to it: this runs twice per navigation (once at onPageCommitVisible
        // against a partial DOM, once at onPageFinished), and the two passes
        // can legitimately disagree - e.g. UNKNOWN first, then
        // PRODUCT_LISTING once the cards exist. classList.add() alone left
        // both classes on <body> and applied both CSS blocks at once.
        function tagBody() {
            var b = document.body;
            if (!b) return;
            b.className = b.className.replace(/\bslp-page-\S+/g, '').trim();
            b.classList.add(cssClass);
        }
        if (document.body) {
            tagBody();
        } else {
            document.addEventListener('DOMContentLoaded', tagBody);
        }

        // Store on window
        window.__SLP_PAGE_TYPE__ = typeEnum;
        window.__SLP_PAGE_CONFIDENCE__ = confidence;

        console.log('[SLP PageDetector] Page: ' + typeEnum + ' (confidence: ' + confidence + ')');

        // Only tell native when the answer actually changed for this document.
        // Both injection points run this file, so an unconditional call
        // reported every page twice - and each call spends the shared 400-call
        // bridge budget that the cart and auth callbacks also draw on.
        var prev = window.__SLP_REPORTED__;
        if (!prev || prev.type !== typeEnum || prev.url !== window.location.href) {
            window.__SLP_REPORTED__ = { type: typeEnum, url: window.location.href };
            callBridge('onPageDetected', [
                typeEnum,
                JSON.stringify({ url: window.location.href, title: document.title, confidence: confidence })
            ]);
        }

        // Context extraction per page type
        if (typeEnum === 'PRODUCT_DETAIL') extractProductDetail();

        // Auth state is read on EVERY page, not just LOGIN/REGISTER/ACCOUNT.
        // Logging out redirects to "/", which classifies as HOME, so gating
        // this on three page types meant no auth callback ever fired after a
        // logout and the app stayed "signed in" until the user happened to
        // land on a login page again.
        extractAuthState();

        syncCartCount();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', run);
    } else {
        run();
    }
})();
