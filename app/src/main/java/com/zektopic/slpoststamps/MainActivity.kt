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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Draw content behind the system bars (edge-to-edge).
        // AppBarLayout + DrawerLayout (fitsSystemWindows=true) handle the inset padding.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Style the system navigation bar so it matches the content surface
        // instead of appearing as a black/grey strip below the app.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = true   // dark icons on light nav bar
        }

        // Set MaterialToolbar as Action Bar for the drawer toggle
        setSupportActionBar(binding.toolbar)

        setupDrawer()
        setupWebView()
        setupSwipeRefresh()
        setupCartFab()
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
                R.id.navigation_home -> {
                    binding.webView.loadUrl(TARGET_URL)
                }
                R.id.navigation_shop -> {
                    binding.webView.loadUrl(TARGET_URL)
                }
                R.id.navigation_cart -> {
                    // Try clicking the site's cart icon via JS; falls back gracefully if not found
                    binding.webView.evaluateJavascript(
                        "document.querySelector('.view-cart-button, .cart-icon, a[href*=\"cart\"]')?.click();",
                        null
                    )
                }
                R.id.navigation_login -> {
                    // The site uses CakePHP-style routing: /users/login
                    // We navigate directly because the header login trigger is hidden by our CSS injection.
                    binding.webView.loadUrl(LOGIN_URL)
                }
                R.id.navigation_signup -> {
                    // CakePHP register page: /users/register
                    binding.webView.loadUrl(REGISTER_URL)
                }
                R.id.navigation_account -> {
                    // Navigate to the stored account URL (/users/view/{id}).
                    // accountUrl is populated by detectAndUpdateAuthState after login.
                    binding.webView.loadUrl(accountUrl)
                }
                R.id.navigation_logout -> {
                    binding.webView.loadUrl(LOGOUT_URL)
                }
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

                // Auth state drives drawer menu visibility
                detectAndUpdateAuthState(view)

                // Hide the cart FAB on pages where the cart IS the page, and on auth pages
                val currentUrl = url ?: ""
                binding.cartFab.visibility = if (
                    currentUrl.contains("/users/login") ||
                    currentUrl.contains("/users/register")
                ) View.GONE else View.VISIBLE

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
     * The cart FAB gives one-tap access to the shopping cart from anywhere in the app.
     * It tries to click the site's own cart link via JS first; if none is found it falls
     * back to a best-guess cart URL (adjust the fallback once the actual cart URL is known).
     */
    private fun setupCartFab() {
        binding.cartFab.setOnClickListener {
            binding.webView.evaluateJavascript(
                """(function(){
                    var cartLink = document.querySelector(
                        'a[href*="cart"], a[href*="orders"], .cart-link,
                         .view-cart-button, .header-cart-btn, [data-cart]'
                    );
                    if (cartLink) { cartLink.click(); return; }
                    window.location.href = 'https://stamps.slpost.gov.lk/orders/add';
                })();""",
                null
            )
        }
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
     * Shows [Login + Sign Up] when the user is a guest, and [Logout] when
     * the user is authenticated. Keeps the drawer menu context-aware without
     * needing a separate backend session.
     */
    private fun updateAuthMenuState(isLoggedIn: Boolean) {
        val menu = binding.navigationView.menu
        // Guest-only items
        menu.findItem(R.id.navigation_login)?.isVisible   = !isLoggedIn
        menu.findItem(R.id.navigation_signup)?.isVisible  = !isLoggedIn
        // Authenticated-only items
        menu.findItem(R.id.navigation_account)?.isVisible = isLoggedIn
        menu.findItem(R.id.navigation_logout)?.isVisible  = isLoggedIn
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