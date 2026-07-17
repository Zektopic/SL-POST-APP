package com.zektopic.slpoststamps

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zektopic.slpoststamps.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val PREFS_NAME       = "slp_state"
        private const val KEY_LOGGED_IN    = "logged_in"
        private const val KEY_ACCOUNT_URL  = "account_url"
        private const val KEY_CART_COUNT   = "cart_count"
        private const val KEY_LAST_URL     = "last_url"
    }

    private lateinit var binding: ActivityMainBinding
    private val TARGET_URL    = "https://stamps.slpost.gov.lk/"
    private val LOGIN_URL     = "https://stamps.slpost.gov.lk/login"
    // GET /users/register returns an empty page — registration is an inline
    // block (#registetModal) on /login, which inject.js scrolls to via hash.
    private val REGISTER_URL  = "https://stamps.slpost.gov.lk/login#registetModal"
    private val LOGOUT_URL    = "https://stamps.slpost.gov.lk/logout/"
    private val TARGET_DOMAIN = "slpost.gov.lk"

    private var accountUrl: String = "https://stamps.slpost.gov.lk/users/"
    private var firstLoadDismissed = false
    private var isUserLoggedIn = false
    private var currentPageType: PageType = PageType.UNKNOWN
    private var cartItemCount: Int = 0

    private lateinit var contentBridge: WebContentBridge
    private lateinit var prefs: SharedPreferences

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars     = true
            isAppearanceLightNavigationBars = true
        }

        setSupportActionBar(binding.toolbar)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupContentBridge()
        setupDrawer()
        setupWebView()
        setupSwipeRefresh()
        setupBottomNav()
        setupBackButtonHandler()
        setupRetryButton()

        // Restore persisted state so the UI is correct before the first page loads
        accountUrl = prefs.getString(KEY_ACCOUNT_URL, accountUrl) ?: accountUrl
        updateAuthMenuState(prefs.getBoolean(KEY_LOGGED_IN, false))
        cartItemCount = prefs.getInt(KEY_CART_COUNT, 0)
        updateCartBadge(cartItemCount)

        if (savedInstanceState != null) {
            // Rotation / process recreation: restore the live WebView state
            // (history, scroll, form input) instead of reloading, and skip
            // the splash overlay.
            firstLoadDismissed = true
            binding.loadingOverlay.visibility = View.GONE
            if (binding.webView.restoreState(savedInstanceState) == null) {
                binding.webView.loadUrl(resumeUrl())
            }
        } else {
            binding.webView.loadUrl(resumeUrl())
        }
    }

    /** Cold-start URL: resume the last visited on-site page, else home. */
    private fun resumeUrl(): String {
        val last = prefs.getString(KEY_LAST_URL, null)
        return if (last != null && Uri.parse(last).host?.contains(TARGET_DOMAIN) == true) last
               else TARGET_URL
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Persist the session cookie and the current page so a process kill
        // doesn't lose the login or the user's place.
        CookieManager.getInstance().flush()
        binding.webView.url?.let { prefs.edit().putString(KEY_LAST_URL, it).apply() }
    }

    private fun setupContentBridge() {
        contentBridge = WebContentBridge(
            onPageDetected = { type, _ ->
                currentPageType = type
                updateToolbarForPageType(type)
            },
            onAuthStateChanged = { isLoggedIn, url ->
                if (url.contains("/users/view/")) {
                    accountUrl = url
                }
                prefs.edit()
                    .putBoolean(KEY_LOGGED_IN, isLoggedIn)
                    .putString(KEY_ACCOUNT_URL, accountUrl)
                    .apply()
                if (isLoggedIn && !isUserLoggedIn) {
                    // Just logged in — make sure the session cookie hits disk now
                    CookieManager.getInstance().flush()
                }
                runOnUiThread { updateAuthMenuState(isLoggedIn) }
            },
            onCartUpdated = { count ->
                cartItemCount = count
                prefs.edit().putInt(KEY_CART_COUNT, count).apply()
                runOnUiThread { updateCartBadge(count) }
            },
            onProductViewed = { title, _, _ ->
                runOnUiThread {
                    if (title.isNotBlank()) {
                        binding.toolbar.subtitle = title.take(46)
                    }
                }
            }
        )
    }

    private fun updateToolbarForPageType(type: PageType) {
        val subtitle = when (type) {
            PageType.HOME             -> "OFFICIAL PHILATELIC BUREAU"
            PageType.PRODUCT_LISTING  -> "Browse Stamps"
            PageType.PRODUCT_DETAIL   -> binding.toolbar.subtitle ?: "Product Details"
            PageType.CART             -> "Shopping Cart"
            PageType.CHECKOUT         -> "Checkout"
            PageType.LOGIN            -> "Sign In"
            PageType.REGISTER         -> "Create Account"
            PageType.ACCOUNT          -> "My Account"
            PageType.STATIC           -> "Information"
            PageType.UNKNOWN          -> binding.toolbar.subtitle ?: "OFFICIAL PHILATELIC BUREAU"
        }
        binding.toolbar.subtitle = subtitle
    }

    private fun updateCartBadge(count: Int) {
        val badge = binding.bottomNavView.getOrCreateBadge(R.id.nav_cart)
        if (count > 0) {
            badge.number = count
            badge.isVisible = true
        } else {
            badge.isVisible = false
        }
    }

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.app_name, R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_login -> binding.webView.loadUrl(LOGIN_URL)
                R.id.navigation_signup -> binding.webView.loadUrl(REGISTER_URL)
                R.id.navigation_account -> binding.webView.loadUrl(accountUrl)
                R.id.navigation_logout -> binding.webView.loadUrl(LOGOUT_URL)
                R.id.navigation_payment_methods -> binding.webView.loadUrl("${TARGET_URL}payment-methods")
                R.id.navigation_standing_order -> binding.webView.loadUrl("${TARGET_URL}how-to-create-standing-order")
                R.id.navigation_downloads -> binding.webView.loadUrl("${TARGET_URL}downloads")
                R.id.navigation_general_info -> binding.webView.loadUrl("${TARGET_URL}general-infor")
                R.id.navigation_terms -> binding.webView.loadUrl("${TARGET_URL}terms-and-conditions/")
                R.id.navigation_contact -> binding.webView.loadUrl("${TARGET_URL}contact-us/")
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun setupWebView() {
        binding.webView.setBackgroundColor(ContextCompat.getColor(this, R.color.md_background))
        binding.webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Session persistence: accept cookies (site login is cookie-based) so
        // the signed-in session survives app restarts once flushed in onPause.
        CookieManager.getInstance().setAcceptCookie(true)

        binding.webView.addJavascriptInterface(contentBridge, "SLPBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = Uri.parse(url).host

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (host != null && host.contains(TARGET_DOMAIN)) {
                        return false
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    }
                }
                return false
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                binding.errorView.visibility = View.GONE
                binding.swipeRefreshLayout.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                // The new document exists but hasn't been drawn yet — injecting
                // here styles the page before the raw site is ever visible.
                injectAssetCss(view, "inject.css")
                injectPageDetector(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false

                // Re-inject CSS to override any late-loaded site styles
                injectAssetCss(view, "inject.css")

                // Inject combined JS: page detector first, then UX enhancements.
                // Combined into a single evaluateJavascript call so page detection
                // runs and sets window.__SLP_PAGE_TYPE__ before inject.js reads it.
                injectCombinedJs(view)

                // Keep bottom nav in sync with the current URL
                syncBottomNavToUrl(url)

                if (!firstLoadDismissed) {
                    firstLoadDismissed = true
                    binding.loadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(600)
                        .setStartDelay(300)
                        .withEndAction { binding.loadingOverlay.visibility = View.GONE }
                        .start()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.swipeRefreshLayout.visibility = View.GONE
                    binding.errorView.visibility = View.VISIBLE
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // Only override if the bridge hasn't already set a page-type-aware subtitle
                if (currentPageType == PageType.UNKNOWN || currentPageType == PageType.HOME) {
                    val url = view?.url ?: ""
                    val isHome = url.trimEnd('/') == TARGET_URL.trimEnd('/')
                    val subtitle = when {
                        isHome || title.isNullOrBlank() -> "OFFICIAL PHILATELIC BUREAU"
                        title.length > 46               -> title.take(43) + "…"
                        else                            -> title
                    }
                    binding.toolbar.subtitle = subtitle
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.md_primary),
            ContextCompat.getColor(this, R.color.md_secondary),
            ContextCompat.getColor(this, R.color.md_tertiary)
        )
        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.md_surface)
        )
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.webView.reload()
        }
    }

    private fun setupBackButtonHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupBottomNav() {
        binding.bottomNavView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.webView.loadUrl(TARGET_URL)

                R.id.nav_browse -> browseCatalog()

                R.id.nav_cart -> navigateToCart()

                R.id.nav_account -> {
                    if (isUserLoggedIn) binding.webView.loadUrl(accountUrl)
                    else               binding.webView.loadUrl(LOGIN_URL)
                }
            }
            true
        }

        binding.bottomNavView.setOnItemReselectedListener { /* no-op */ }
    }

    private fun navigateToCart() {
        binding.webView.loadUrl("https://stamps.slpost.gov.lk/cart-view")
    }

    /**
     * Browse = the product catalog section on the home page. If we're already
     * there, scroll to it; otherwise load home with the #slp-browse hash that
     * inject.js scrolls to after the page renders.
     */
    private fun browseCatalog() {
        val js = """(function(){
            if (location.pathname !== '/') return 'navigate';
            var t = document.querySelector('.nav-tabs, .features_items, .catagories-heading');
            if (!t) return 'navigate';
            t.scrollIntoView({ behavior: 'smooth', block: 'start' });
            return 'scrolled';
        })();"""
        binding.webView.evaluateJavascript(js) { result ->
            if (result == null || !result.contains("scrolled")) {
                binding.webView.loadUrl("$TARGET_URL#slp-browse")
            }
        }
    }

    private fun syncBottomNavToUrl(url: String?) {
        val u = url ?: return
        val id = when {
            u.contains("/orders") || u.contains("/cart")   -> R.id.nav_cart
            u.contains("/users/") || u.contains("/login")  -> R.id.nav_account
            else                                           -> R.id.nav_home
        }
        binding.bottomNavView.setOnItemSelectedListener(null)
        binding.bottomNavView.selectedItemId = id
        setupBottomNav()
    }

    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.swipeRefreshLayout.visibility = View.VISIBLE
            binding.webView.reload()
        }
    }

    // ── Auth state is now driven by the bridge (JS → Kotlin) instead of polling ──

    private fun updateAuthMenuState(isLoggedIn: Boolean) {
        isUserLoggedIn = isLoggedIn

        val menu = binding.navigationView.menu
        menu.findItem(R.id.navigation_login)?.isVisible   = !isLoggedIn
        menu.findItem(R.id.navigation_signup)?.isVisible  = !isLoggedIn
        menu.findItem(R.id.navigation_account)?.isVisible = isLoggedIn
        menu.findItem(R.id.navigation_logout)?.isVisible  = isLoggedIn

        binding.bottomNavView.menu.findItem(R.id.nav_account)?.title =
            if (isLoggedIn) "Account" else "Login"
    }

    // ── Asset injection helpers ──

    /**
     * Injects the stylesheet as a Base64 payload so no character in the CSS
     * can break the surrounding JS string literal.
     */
    private fun injectAssetCss(view: WebView?, filename: String) {
        val css = readAsset(filename) ?: run {
            Log.w(TAG, "injectAssetCss: asset $filename not found")
            return
        }
        val b64 = Base64.encodeToString(css.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = """(function(){
            try {
                var css = new TextDecoder('utf-8').decode(
                    Uint8Array.from(atob('$b64'), function(c){ return c.charCodeAt(0); }));
                var existing = document.getElementById('slp-injected-css');
                if (existing) { existing.remove(); }
                var s = document.createElement('style');
                s.id = 'slp-injected-css';
                s.textContent = css;
                (document.head || document.documentElement).appendChild(s);
                return 'css-ok';
            } catch (e) { return 'css-error: ' + e.message; }
        })();"""
        view?.evaluateJavascript(js) { result -> Log.d(TAG, "injectAssetCss($filename): $result") }
    }

    /**
     * Injects page-detector.js at first paint (onPageCommitVisible) so the
     * body class (slp-page-*) is set before the user sees the page.
     */
    private fun injectPageDetector(view: WebView?) {
        val js = readAsset("page-detector.js") ?: return
        injectJsWithLogging(view, js, "page-detector")
    }

    /**
     * Combines page-detector.js and inject.js into a single evaluation so that
     * page detection runs and sets window.__SLP_PAGE_TYPE__ before inject.js
     * reads it — avoiding async ordering issues between separate evaluateJavascript calls.
     */
    private fun injectCombinedJs(view: WebView?) {
        val detector = readAsset("page-detector.js") ?: ""
        val inject   = readAsset("inject.js") ?: ""
        injectJsWithLogging(view, "$detector\n$inject", "combined")
    }

    /**
     * Runs the script in an IIFE with a try/catch so failures surface in
     * logcat (tag MainActivity / WebContentBridge) instead of dying silently.
     */
    private fun injectJsWithLogging(view: WebView?, script: String, label: String) {
        val wrapped = """(function(){
            try {
                $script
                return '$label-ok';
            } catch (e) {
                if (window.SLPBridge && SLPBridge.log) { SLPBridge.log('$label error: ' + e.message); }
                return '$label-error: ' + e.message;
            }
        })();"""
        view?.evaluateJavascript(wrapped) { result -> Log.d(TAG, "inject($label): $result") }
    }

    private fun readAsset(filename: String): String? {
        return try {
            assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
