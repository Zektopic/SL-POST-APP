package com.zektopic.slpoststamps

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebStorage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
        private const val BRIDGE_NAME = "SLPBridge"

        /** Schemes handed to another app rather than rendered in the WebView. */
        private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo", "market")

        /**
         * Path fragments that perform an action on GET. Persisting one of these
         * as the resume URL would silently repeat the action on next launch.
         */
        private val NON_RESUMABLE_PATHS =
            listOf("/add-to-cart", "/logout", "/remove", "/delete", "/cart-add")

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
    private val TARGET_HOST   = "stamps.slpost.gov.lk"
    private val TARGET_SUFFIX = ".slpost.gov.lk"
    private val DEFAULT_ACCOUNT_URL = "https://stamps.slpost.gov.lk/users/"

    // Written from the WebView JavaBridge thread, read from the UI thread.
    @Volatile private var accountUrl: String = DEFAULT_ACCOUNT_URL
    private var firstLoadDismissed = false
    @Volatile private var isUserLoggedIn = false
    @Volatile private var currentPageType: PageType = PageType.UNKNOWN
    @Volatile private var cartItemCount: Int = 0

    private lateinit var contentBridge: WebContentBridge

    /** Set while a logout navigation is in flight, so local state is cleared once it settles. */
    private var pendingLogout = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    /** Download deferred until the legacy storage permission comes back (API <= 28). */
    private var pendingDownload: PendingDownload? = null
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<String>

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )
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

        registerActivityLaunchers()

        setupContentBridge()
        setupDrawer()
        setupWebView()
        setupSwipeRefresh()
        setupBottomNav()
        setupBackButtonHandler()
        setupRetryButton()
        setupDownloads()

        // Restore persisted state so the UI is correct before the first page loads.
        // Re-validate on the way out of prefs: a value written by an older build
        // (or by a hostile page before validation existed) must not be trusted.
        accountUrl = prefs.getString(KEY_ACCOUNT_URL, null)
            ?.takeIf { isTrustedUrl(it) }
            ?: DEFAULT_ACCOUNT_URL
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

    /**
     * True only for the storefront host or a real subdomain of it.
     *
     * Substring matching (`host.contains("slpost.gov.lk")`) is NOT sufficient:
     * it also accepts attacker-controlled hosts such as
     * `slpost.gov.lk.example.com`, which would then load inside the WebView
     * with the SLPBridge interface attached.
     */
    private fun isTargetHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == TARGET_HOST || h.endsWith(TARGET_SUFFIX)
    }

    /** True only for an https URL on a trusted host. Rejects other schemes outright. */
    private fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && isTargetHost(uri.host)
    }

    /**
     * Hands a URL to whatever app can open it. Guarded: a device with no
     * handler for the scheme throws ActivityNotFoundException, which would
     * otherwise take the whole app down.
     */
    private fun openExternally(url: String) {
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!ok) {
            Log.w(TAG, "openExternally: no handler for $url")
            Toast.makeText(this, R.string.error_no_app_to_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * True if the URL is safe to reopen on a cold start. This site mutates
     * state via GET (/add-to-cart, /logout), so replaying the last URL blindly
     * would re-add items to the cart or sign the user out on next launch.
     */
    private fun isResumable(url: String): Boolean {
        if (!isTrustedUrl(url)) return false
        val path = Uri.parse(url).path?.lowercase() ?: return false
        return NON_RESUMABLE_PATHS.none { path.contains(it) }
    }

    /** Cold-start URL: resume the last visited on-site page, else home. */
    private fun resumeUrl(): String {
        val last = prefs.getString(KEY_LAST_URL, null)
        return if (isTrustedUrl(last)) last!! else TARGET_URL
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Persist the session cookie and the current page so a process kill
        // doesn't lose the login or the user's place. flush() is intentionally
        // synchronous here: deferring it risks losing the session if the
        // process is killed immediately after backgrounding.
        CookieManager.getInstance().flush()
        binding.webView.url
            ?.takeIf { isResumable(it) }
            ?.let { prefs.edit().putString(KEY_LAST_URL, it).apply() }
        // Suspend JS timers, animations and media while backgrounded.
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    /**
     * Without this the WebView outlives the Activity. It holds the Activity as
     * its context, and addJavascriptInterface pins a chain of
     * WebView -> WebContentBridge -> callback lambdas -> MainActivity, so every
     * configuration change (rotation) leaked an entire Activity and its view
     * hierarchy.
     *
     * The absence of android:configChanges is correct - the missing teardown
     * was the bug, so do not "fix" this by suppressing recreation instead.
     */
    override fun onDestroy() {
        binding.webView.let { web ->
            web.removeJavascriptInterface(BRIDGE_NAME)
            web.stopLoading()
            web.webChromeClient = null
            (web.parent as? ViewGroup)?.removeView(web)
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }

    private fun setupContentBridge() {
        contentBridge = WebContentBridge(
            pageDetectedCallback = { type, _ ->
                currentPageType = type
                // Bridge callbacks arrive on the WebView JavaBridge thread;
                // touching a View from there throws CalledFromWrongThreadException.
                runOnUiThread { updateToolbarForPageType(type) }
            },
            authStateChangedCallback = { isLoggedIn, url ->
                // `url` is page-controlled content. Only adopt it if it is an
                // https URL on a trusted host AND looks like an account page —
                // it is persisted and later handed to WebView.loadUrl().
                if (isTrustedUrl(url) && Uri.parse(url).path?.contains("/users/view/") == true) {
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
            cartUpdatedCallback = { count ->
                cartItemCount = count
                prefs.edit().putInt(KEY_CART_COUNT, count).apply()
                runOnUiThread { updateCartBadge(count) }
            },
            productViewedCallback = { title, _, _ ->
                runOnUiThread {
                    if (title.isNotBlank()) {
                        binding.toolbar.subtitle = title.take(46)
                    }
                }
            }
        )
    }

    private fun updateToolbarForPageType(type: PageType) {
        val subtitle: CharSequence = when (type) {
            PageType.HOME             -> getString(R.string.subtitle_default)
            PageType.PRODUCT_LISTING  -> getString(R.string.subtitle_browse)
            PageType.PRODUCT_DETAIL   -> binding.toolbar.subtitle ?: getString(R.string.subtitle_product)
            PageType.CART             -> getString(R.string.subtitle_cart)
            PageType.CHECKOUT         -> getString(R.string.subtitle_checkout)
            PageType.LOGIN            -> getString(R.string.subtitle_login)
            PageType.REGISTER         -> getString(R.string.subtitle_register)
            PageType.ACCOUNT          -> getString(R.string.subtitle_account)
            PageType.STATIC           -> getString(R.string.subtitle_static)
            PageType.UNKNOWN          -> binding.toolbar.subtitle ?: getString(R.string.subtitle_default)
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
            R.string.drawer_open, R.string.drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_login -> binding.webView.loadUrl(LOGIN_URL)
                R.id.navigation_signup -> binding.webView.loadUrl(REGISTER_URL)
                R.id.navigation_account -> binding.webView.loadUrl(accountUrl)
                R.id.navigation_logout -> logOut()
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

            // Never load http subresources into an https page. The platform
            // default (cleartext blocked) only applies from API 28, and minSdk
            // here is 24, so this must be set explicitly.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // These are already safe by default at targetSdk 36, but pin them
            // so a future targetSdk change cannot silently reopen them.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            binding.webView.settings.safeBrowsingEnabled = true
        }

        // Session persistence: accept cookies (site login is cookie-based) so
        // the signed-in session survives app restarts once flushed in onPause.
        CookieManager.getInstance().setAcceptCookie(true)

        binding.webView.addJavascriptInterface(contentBridge, BRIDGE_NAME)

        binding.webView.webViewClient = object : WebViewClient() {
            /**
             * Default-deny. Only https on a trusted host stays in the WebView;
             * off-site http(s) is handed to the browser; every other scheme
             * (content://, intent://, blob:, file:, …) is dropped.
             */
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()

                if (isTrustedUrl(url)) return false

                val scheme = uri.scheme?.lowercase()
                // Off-site web links, and the handful of schemes a content page
                // legitimately uses, go to the relevant app. Everything else
                // (intent://, content://, file://, blob:, javascript: …) is
                // dropped rather than handed back to the WebView, which would
                // render ERR_UNKNOWN_URL_SCHEME.
                if (scheme == "http" || scheme == "https" || scheme in EXTERNAL_SCHEMES) {
                    openExternally(url)
                    return true
                }
                Log.w(TAG, "shouldOverrideUrlLoading: dropped scheme=$scheme")
                return true
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

                // The logout navigation has settled; the request carried the
                // session cookie, so it is now safe to clear it locally.
                clearLocalSession()

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
                    showErrorState()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Without this a 5xx renders the raw server error page inside
                // the app chrome as though it were a normal screen.
                val status = errorResponse?.statusCode ?: return
                if (request?.isForMainFrame == true && status >= 500) {
                    showErrorState()
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // Answer any previous outstanding callback first, otherwise the
                // WebView refuses to open the picker again.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = params?.createIntent()
                if (intent == null) {
                    filePathCallback = null
                    return false
                }
                return runCatching {
                    fileChooserLauncher.launch(intent)
                    true
                }.getOrElse {
                    Log.w(TAG, "onShowFileChooser: no picker available", it)
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    false
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // Only override if the bridge hasn't already set a page-type-aware subtitle
                if (currentPageType == PageType.UNKNOWN || currentPageType == PageType.HOME) {
                    val url = view?.url ?: ""
                    val isHome = url.trimEnd('/') == TARGET_URL.trimEnd('/')
                    val subtitle = when {
                        isHome || title.isNullOrBlank() -> getString(R.string.subtitle_default)
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

    /**
     * onReceivedError previously showed the error view but left the splash
     * overlay up. loadingOverlay is the last child of the CoordinatorLayout at
     * match_parent, so it drew over everything - including the retry button -
     * and was only dismissed in onPageFinished, which may not fire on a failed
     * first load. Dismissing it here gives the user a way out regardless.
     */
    private fun showErrorState() {
        firstLoadDismissed = true
        binding.loadingOverlay.visibility = View.GONE
        binding.swipeRefreshLayout.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isRefreshing = false
        binding.progressBar.visibility = View.GONE
        clearLocalSession()
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
            getString(if (isLoggedIn) R.string.nav_account else R.string.nav_login)
    }


    // ── Activity result plumbing ──

    /**
     * Both launchers must be registered before the Activity reaches STARTED,
     * i.e. during onCreate - registering lazily from a WebView callback throws.
     */
    private fun registerActivityLaunchers() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            // Must always be answered, even on cancel: leaving the callback
            // unanswered permanently disables every later file input.
            cb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

        storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val queued = pendingDownload
            pendingDownload = null
            if (granted && queued != null) {
                enqueueDownload(queued)
            } else if (!granted) {
                Toast.makeText(this, R.string.error_download_needs_storage, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Downloads ──

    /**
     * A WebView with no DownloadListener silently ignores download
     * navigations, which is why the drawer's Downloads section did nothing.
     */
    private fun setupDownloads() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val request = PendingDownload(url, userAgent, contentDisposition, mimeType)
            if (needsLegacyStoragePermission()) {
                pendingDownload = request
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueueDownload(request)
            }
        }
    }

    /** Only API <= 28 needs WRITE_EXTERNAL_STORAGE to write to public Downloads. */
    private fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    private fun enqueueDownload(download: PendingDownload) {
        // Only download from the storefront - a hostile page must not be able
        // to make the app fetch arbitrary hosts with the user's cookies.
        if (!isTrustedUrl(download.url)) {
            Log.w(TAG, "enqueueDownload: refusing untrusted URL")
            return
        }
        val fileName = URLUtil.guessFileName(
            download.url, download.contentDisposition, download.mimeType
        )
        val ok = runCatching {
            val request = DownloadManager.Request(Uri.parse(download.url)).apply {
                setMimeType(download.mimeType)
                download.userAgent?.let { addRequestHeader("User-Agent", it) }
                // Carry the session cookie so authenticated receipts and
                // invoices download as the signed-in user rather than 403ing.
                CookieManager.getInstance().getCookie(download.url)
                    ?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setDescription(getString(R.string.download_in_progress))
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.isSuccess

        Toast.makeText(
            this,
            if (ok) R.string.download_started else R.string.error_download_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── Logout ──

    /**
     * Navigating to /logout/ is not enough on its own: the server redirects to
     * "/", which the detector classifies as HOME, and extractAuthState() only
     * runs on LOGIN/REGISTER/ACCOUNT - so no auth callback ever arrived and the
     * app stayed "signed in" forever, including across restarts.
     *
     * Local state is therefore reset here rather than waiting on the bridge,
     * and the cookie jar is cleared once the navigation settles (in
     * onPageFinished or onReceivedError) so the outbound request still carries
     * the session cookie the server needs in order to invalidate it.
     */
    private fun logOut() {
        pendingLogout = true
        binding.webView.loadUrl(LOGOUT_URL)
        accountUrl = DEFAULT_ACCOUNT_URL
        cartItemCount = 0
        prefs.edit().clear().apply()
        updateAuthMenuState(false)
        updateCartBadge(0)
    }

    /** Clears client-side session state. Safe to call more than once. */
    private fun clearLocalSession() {
        if (!pendingLogout) return
        pendingLogout = false
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
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
        // inject.js runs a getComputedStyle sweep over the whole page and logs
        // one bridge message per full-viewport element. Useful for chasing a
        // black screen, far too chatty for release - and it shares the 400-call
        // bridge budget with the cart and auth callbacks, so left on it can
        // silently starve them. Opt in on debuggable builds only.
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val prelude = "window.__SLP_DIAG__ = $debuggable;\n"
        injectJsWithLogging(view, "$prelude$detector\n$inject", "combined")
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
