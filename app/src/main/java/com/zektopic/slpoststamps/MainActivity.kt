package com.zektopic.slpoststamps

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
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

    private lateinit var binding: ActivityMainBinding
    private val TARGET_URL    = "https://stamps.slpost.gov.lk/"
    private val LOGIN_URL     = "https://stamps.slpost.gov.lk/users/login"
    private val REGISTER_URL  = "https://stamps.slpost.gov.lk/users/register"
    private val LOGOUT_URL    = "https://stamps.slpost.gov.lk/logout/"
    private val TARGET_DOMAIN = "slpost.gov.lk"

    // Stores the logged-in user's profile URL once detected from the DOM (/users/view/{id}).
    private var accountUrl: String = "https://stamps.slpost.gov.lk/users/"

    // True once the branded loading overlay has been dismissed after the first page load.
    // Subsequent in-app navigations use only the progress bar — not the full-screen overlay.
    private var firstLoadDismissed = false

    // Tracks login state so the Account tab knows where to navigate.
    private var isUserLoggedIn = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Draw content behind the system bars (edge-to-edge).
        // AppBarLayout + DrawerLayout (fitsSystemWindows=true) handle the inset padding.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Style system bars: toolbar + bottom nav are both light surfaces,
        // so we need dark icons on both the status bar and the navigation bar.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars     = true   // dark icons on white toolbar
            isAppearanceLightNavigationBars = true   // dark icons on light nav bar
        }

        // Set MaterialToolbar as Action Bar for the drawer toggle
        setSupportActionBar(binding.toolbar)

        setupDrawer()
        setupWebView()
        setupSwipeRefresh()
        setupBottomNav()
        setupBackButtonHandler()
        setupRetryButton()

        // Load the initial URL into the WebWrapper
        binding.webView.loadUrl(TARGET_URL)
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
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun setupWebView() {
        // ── Match WebView background to the app surface colour ──
        // This eliminates the jarring white flash between page navigations because
        // the WebView canvas colour matches the injected CSS body background (#FDF8F9).
        binding.webView.setBackgroundColor(ContextCompat.getColor(this, R.color.md_background))

        // Disable the default Android overscroll glow/bounce so the WebView feels native.
        binding.webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        // Enable necessary settings for modern rendering and functions
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // Essential for the shopping cart and modern JS frameworks
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false // Hide the clunky native zoom buttons
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = Uri.parse(url).host
                
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Keep navigation inside the app domain safely, avoiding system browser kicks and bugs
                    if (host != null && host.contains(TARGET_DOMAIN)) {
                        return false // Let WebView handle it natively
                    } else {
                        // External links: pop them out safely via intents so we do not break internal routing or get stuck on white screens
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    }
                }
                return false
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                // To avoid white screen completely if slpost's domain ssl cert is weakly configured or expired
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Show ProgressBar visually
                binding.progressBar.visibility = View.VISIBLE
                binding.errorView.visibility = View.GONE
                binding.swipeRefreshLayout.visibility = View.VISIBLE

                // Early CSS injection — prevents flash of unstyled content (FOUC).
                // The CSS hides headers/footers and sets colours before the page renders.
                injectAssetCss(view, "inject.css")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false

                // Re-inject CSS (overrides any late-loaded site styles)
                injectAssetCss(view, "inject.css")

                // Inject UX enhancements
                injectAssetJs(view, "inject.js")

                // Auth state drives drawer + bottom nav visibility
                detectAndUpdateAuthState(view)

                // Keep the bottom nav selection in sync with the current URL
                syncBottomNavToUrl(url)

                // ── Dismiss the branded splash overlay once on the very first page load ──
                // Subsequent navigations use only the thin progress bar.
                if (!firstLoadDismissed) {
                    firstLoadDismissed = true
                    binding.loadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(600)
                        .setStartDelay(300)   // give the CSS injection a moment to apply
                        .withEndAction { binding.loadingOverlay.visibility = View.GONE }
                        .start()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                // Only intercept main frame errors (prevents broken images killing the whole view)
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

            /**
             * Updates the toolbar subtitle with the page title so the user always
             * knows where they are — just like a native app's screen header.
             * Falls back to the brand tagline on the home page or blank titles.
             */
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
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

    private fun setupSwipeRefresh() {
        // Brand colours for the pull-to-refresh spinner
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
        // Intercept native Back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    // Close sidebar if open
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else if (binding.webView.canGoBack()) {
                    // Perform Web History back navigation
                    binding.webView.goBack()
                } else {
                    // Remove intercept, default system behavior
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    
    /**
     * Wires up the native bottom navigation bar.
     *
     * Home    → TARGET_URL (catalogue root)
     * Browse  → TARGET_URL (same catalogue — can be refined once a search URL is known)
     * Cart    → tries JS click on the site's cart link; falls back to /orders/add
     * Account → Login page when guest; /users/view/{id} when authenticated
     *
     * Reselecting the current tab does nothing (prevents unwanted reloads).
     */
    private fun setupBottomNav() {
        binding.bottomNavView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.webView.loadUrl(TARGET_URL)

                R.id.nav_browse -> binding.webView.loadUrl(TARGET_URL)

                R.id.nav_cart -> binding.webView.evaluateJavascript(
                    """(function(){
                        var link = document.querySelector(
                            'a[href*="cart"], a[href*="orders"], .cart-link,
                             .view-cart-button, .header-cart-btn, [data-cart]'
                        );
                        if (link) { link.click(); return; }
                        window.location.href = 'https://stamps.slpost.gov.lk/orders/add';
                    })();""",
                    null
                )

                R.id.nav_account -> {
                    if (isUserLoggedIn) binding.webView.loadUrl(accountUrl)
                    else               binding.webView.loadUrl(LOGIN_URL)
                }
            }
            true
        }

        // Reselecting the active tab does nothing — prevents accidental reloads.
        binding.bottomNavView.setOnItemReselectedListener { /* no-op */ }
    }

    /**
     * Keeps the bottom nav's selected item in sync with wherever the WebView
     * currently is, so tapping Back always returns to the right highlighted tab.
     */
    private fun syncBottomNavToUrl(url: String?) {
        val u = url ?: return
        val id = when {
            u.contains("/orders") || u.contains("/cart") -> R.id.nav_cart
            u.contains("/users/")                        -> R.id.nav_account
            else                                         -> R.id.nav_home
        }
        // Avoid triggering the item-selected listener while syncing.
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

    /**
     * Runs a small JS snippet in the WebView to check whether a logged-in user
     * session is active on the page, then updates the drawer menu visibility so
     * that guests see [Login / Sign Up] and authenticated users see [Logout].
     *
     * Detection strategy: look for any element that only exists for logged-in users —
     * typically a logout link, a "My Account" link, or a user-greeting element.
     * Returns the string "true" or "false" which we parse in the callback.
     */
    private fun detectAndUpdateAuthState(view: WebView?) {
        // Strategy 1 (fastest): current URL tells us login state immediately.
        val currentUrl = view?.url ?: ""
        when {
            currentUrl.contains("/users/view/") || currentUrl.contains("/users/edit/") -> {
                // We're ON the account page — capture the exact URL for the drawer shortcut.
                accountUrl = currentUrl
                updateAuthMenuState(isLoggedIn = true)
                return
            }
            currentUrl.contains("/users/login") || currentUrl.contains("/users/register") -> {
                updateAuthMenuState(isLoggedIn = false)
                return
            }
        }

        // Strategy 2: the header is visually hidden but still in the DOM.
        // For logged-in users the header contains a logout link and a /users/view/{id} link.
        // We read both signals and also capture the account URL if found.
        val detectJs = """
            (function(){
                // Look for a link to the user's profile page
                var accountLink = document.querySelector('a[href*="/users/view/"]');
                if(accountLink) return 'loggedin:' + accountLink.href;

                // Fallback signals
                var signals = [
                    'a[href*="/logout"]',
                    '.logout-link',
                    '#logout',
                    '.user-logged-in',
                    '.auth-user',
                    '.user-nav'
                ];
                for(var i = 0; i < signals.length; i++){
                    if(document.querySelector(signals[i])) return 'loggedin:';
                }
                return 'guest';
            })();
        """.trimIndent()

        view?.evaluateJavascript(detectJs) { result ->
            val raw = result?.trim('"') ?: "guest"
            val isLoggedIn = raw.startsWith("loggedin")
            if (isLoggedIn) {
                // Extract account URL if the DOM gave us one (format: "loggedin:https://...")
                val extracted = raw.removePrefix("loggedin:").trim()
                if (extracted.contains("/users/view/")) {
                    accountUrl = extracted
                }
            }
            runOnUiThread { updateAuthMenuState(isLoggedIn) }
        }
    }

    /**
     * Updates both the navigation drawer (auth items) and the bottom nav
     * Account tab label to reflect the current login state.
     *
     * Guest:         drawer shows [Login / Sign Up];  Account tab labelled "Login"
     * Authenticated: drawer shows [My Account / Logout]; Account tab labelled "Account"
     */
    private fun updateAuthMenuState(isLoggedIn: Boolean) {
        isUserLoggedIn = isLoggedIn

        // Drawer — guest-only items
        val menu = binding.navigationView.menu
        menu.findItem(R.id.navigation_login)?.isVisible   = !isLoggedIn
        menu.findItem(R.id.navigation_signup)?.isVisible  = !isLoggedIn
        // Drawer — authenticated-only items
        menu.findItem(R.id.navigation_account)?.isVisible = isLoggedIn
        menu.findItem(R.id.navigation_logout)?.isVisible  = isLoggedIn

        // Bottom nav — update Account tab label so the user knows what to expect
        binding.bottomNavView.menu.findItem(R.id.nav_account)?.title =
            if (isLoggedIn) "Account" else "Login"
    }

    /**
     * Reads a CSS file from src/main/assets, then injects it as a <style> tag
     * via JavaScript. Using assets avoids brittle inline string escaping in Kotlin.
     *
     * Escaping strategy:
     *   - backslashes doubled first
     *   - double-quotes escaped
     *   - newlines collapsed to \n so the final JS is one logical line
     */
    private fun injectAssetCss(view: WebView?, filename: String) {
        val css = readAsset(filename) ?: return
        val escaped = css
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
        val js = """(function(){
            var s=document.createElement('style');
            s.id='slp-injected-css';
            s.innerHTML="$escaped";
            var existing=document.getElementById('slp-injected-css');
            if(existing){existing.remove();}
            document.head.appendChild(s);
        })();"""
        view?.evaluateJavascript(js, null)
    }

    /**
     * Reads a JS file from src/main/assets and evaluates it in the WebView.
     * The file is wrapped in an IIFE so it cannot pollute the global scope.
     */
    private fun injectAssetJs(view: WebView?, filename: String) {
        val js = readAsset(filename) ?: return
        view?.evaluateJavascript("(function(){\n$js\n})();", null)
    }

    /** Reads a file from assets and returns its content, or null on error. */
    private fun readAsset(filename: String): String? {
        return try {
            assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null // asset not found or unreadable — silently skip
        }
    }
}