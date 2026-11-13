package com.zektopic.slpoststamps

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zektopic.slpoststamps.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BRIDGE_NAME = "SLPBridge"

        /** Schemes handed to another app rather than rendered in the WebView. */
        private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo", "market")

        private const val PREFS_NAME = "slp_state"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_ACCOUNT_URL = "account_url"
        private const val KEY_CART_COUNT = "cart_count"
        private const val KEY_LAST_URL = "last_url"

        /** The loading screen never outlives this, whatever the page does. */
        private const val SPLASH_TIMEOUT_MS = 12_000L

        /** Page-supplied text shown in native UI is cut to these lengths. */
        private const val MAX_DIALOG_CHARS = 600
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_SEARCH_CHARS = 100
    }

    /** Why the main frame failed to load; drives the error screen's wording. */
    private enum class LoadFailure(
        @param:DrawableRes val icon: Int,
        @param:StringRes val title: Int,
        @param:StringRes val message: Int
    ) {
        OFFLINE(R.drawable.ic_no_connection, R.string.error_offline_title, R.string.error_offline_message),
        UNREACHABLE(R.drawable.ic_no_connection, R.string.error_unreachable_title, R.string.error_unreachable_message),
        SERVER(R.drawable.ic_error, R.string.error_server_title, R.string.error_server_message),
        GENERIC(R.drawable.ic_error, R.string.error_generic_title, R.string.error_generic_message)
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var contentBridge: WebContentBridge

    // Written from the WebView JavaBridge thread, read from the UI thread.
    @Volatile private var accountUrl: String = Storefront.DEFAULT_ACCOUNT
    @Volatile private var isUserLoggedIn = false
    @Volatile private var currentPageType: PageType = PageType.UNKNOWN
    @Volatile private var cartItemCount: Int = 0

    // UI thread only from here down.
    private var productTitle: String? = null
    private var splashDismissed = false
    private var mainFrameFailed = false
    private var lastFailure: LoadFailure? = null
    private var webViewDestroyed = false
    private var syncingBottomNav = false
    private var searchItem: MenuItem? = null

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

    private val splashTimeout = Runnable { dismissSplash() }

    /** Reloads by itself when the connection comes back while "You're offline" is showing. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (mainFrameFailed && lastFailure == LoadFailure.OFFLINE) retryLoad()
            }
        }
    }

    // ── Lifecycle ──

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The system bars are transparent over the app's own surfaces, so
        // their icons have to contrast with whichever theme is showing. These
        // were hard-coded to "dark icons", which vanished on the dark theme.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode()
            isAppearanceLightNavigationBars = !isNightMode()
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
            ?: Storefront.DEFAULT_ACCOUNT
        updateAuthMenuState(prefs.getBoolean(KEY_LOGGED_IN, false))
        cartItemCount = prefs.getInt(KEY_CART_COUNT, 0)
        updateCartBadge(cartItemCount)

        if (savedInstanceState != null) {
            // Rotation / theme change / process recreation: restore the live
            // WebView state (history, scroll, form input) instead of
            // reloading, and skip the loading screen.
            dismissSplash(animate = false)
            if (binding.webView.restoreState(savedInstanceState) == null) {
                binding.webView.loadUrl(resumeUrl())
            }
        } else {
            binding.loadingOverlay.postDelayed(splashTimeout, SPLASH_TIMEOUT_MS)
            binding.webView.loadUrl(resumeUrl())
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { Log.w(TAG, "could not watch connectivity", it) }
    }

    override fun onStop() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDestroyed) binding.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Persist the session cookie and the current page so a process kill
        // doesn't lose the login or the user's place. flush() is intentionally
        // synchronous here: deferring it risks losing the session if the
        // process is killed immediately after backgrounding.
        CookieManager.getInstance().flush()
        if (webViewDestroyed) return
        binding.webView.url
            ?.takeIf { isResumable(it) }
            ?.let { url -> prefs.edit { putString(KEY_LAST_URL, url) } }
        // Suspend JS timers, animations and media while backgrounded.
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) binding.webView.onResume()
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
        binding.loadingOverlay.removeCallbacks(splashTimeout)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (!webViewDestroyed) {
            webViewDestroyed = true
            binding.webView.let { web ->
                web.removeJavascriptInterface(BRIDGE_NAME)
                web.stopLoading()
                web.webChromeClient = null
                (web.parent as? ViewGroup)?.removeView(web)
                web.removeAllViews()
                web.destroy()
            }
        }
        super.onDestroy()
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ── URL rules ──

    /** True only for an https URL on a trusted host. Rejects other schemes outright. */
    private fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && Storefront.isTrustedHost(uri.host)
    }

    /**
     * True only for an https URL on the storefront itself. This is the test
     * for injecting our CSS and JS: `isTrustedUrl` also admits other
     * slpost.gov.lk sites, which are not ours to restyle.
     */
    private fun isStorefrontUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && Storefront.isStorefrontHost(uri.host)
    }

    /**
     * True if the URL is safe to reopen on a cold start. This site mutates
     * state via GET (/clear-cart, /logout), so replaying the last URL blindly
     * would empty the cart or sign the user out on next launch.
     */
    private fun isResumable(url: String): Boolean =
        isStorefrontUrl(url) && ResumePolicy.isResumablePath(url.toUri().path)

    /** Cold-start URL: resume the last visited on-site page, else home. */
    private fun resumeUrl(): String {
        // isResumable, not just isTrustedUrl: a URL stored by an older build
        // was filtered against a shorter list than the current one.
        val last = prefs.getString(KEY_LAST_URL, null)
        return if (last != null && isResumable(last)) last else Storefront.HOME
    }

    /** The page to share or open elsewhere: where the WebView is, without our fragments. */
    private fun currentPageUrl(): String =
        binding.webView.url
            ?.takeIf { isStorefrontUrl(it) }
            ?.substringBefore('#')
            ?: Storefront.HOME

    /**
     * Hands a URL to whatever app can open it. Guarded: a device with no
     * handler for the scheme throws ActivityNotFoundException, which would
     * otherwise take the whole app down.
     */
    private fun openExternally(url: String) {
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.isSuccess
        if (!ok) {
            Log.w(TAG, "openExternally: no handler for the link")
            Toast.makeText(this, R.string.error_no_app_to_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Bridge (page -> app) ──

    private fun setupContentBridge() {
        contentBridge = WebContentBridge(
            pageDetectedCallback = { type, _ ->
                currentPageType = type
                // Bridge callbacks arrive on the WebView JavaBridge thread;
                // touching a View from there throws CalledFromWrongThreadException.
                runOnUiThread {
                    if (type != PageType.PRODUCT_DETAIL) productTitle = null
                    refreshSubtitle()
                }
            },
            authStateChangedCallback = { isLoggedIn, url ->
                // `url` is page-controlled content. Only adopt it if it is an
                // https URL on the storefront AND looks like an account page —
                // it is persisted and later handed to WebView.loadUrl().
                if (isStorefrontUrl(url) && url.toUri().path?.contains("/users/view/") == true) {
                    accountUrl = url
                }
                prefs.edit {
                    putBoolean(KEY_LOGGED_IN, isLoggedIn)
                    putString(KEY_ACCOUNT_URL, accountUrl)
                }
                if (isLoggedIn && !isUserLoggedIn) {
                    // Just signed in — make sure the session cookie hits disk now.
                    CookieManager.getInstance().flush()
                }
                runOnUiThread { updateAuthMenuState(isLoggedIn) }
            },
            cartUpdatedCallback = { count ->
                val safe = count.coerceIn(0, 999)
                cartItemCount = safe
                prefs.edit { putInt(KEY_CART_COUNT, safe) }
                runOnUiThread { updateCartBadge(safe) }
            },
            productViewedCallback = { title, _, _ ->
                val clean = title.trim().take(MAX_TITLE_CHARS).ifBlank { null }
                runOnUiThread {
                    productTitle = clean
                    refreshSubtitle()
                }
            },
            pullToRefreshCallback = { enabled ->
                runOnUiThread { binding.swipeRefreshLayout.isEnabled = enabled }
            }
        )
    }

    /**
     * The subtitle says where the user is. It is derived from the page type
     * and nothing else, so it cannot go stale: it used to keep a product's
     * name after navigating to a page the detector could not classify.
     */
    private fun refreshSubtitle() {
        binding.toolbar.subtitle = when (currentPageType) {
            PageType.PRODUCT_DETAIL -> productTitle ?: getString(R.string.subtitle_product)
            PageType.PRODUCT_LISTING -> getString(R.string.subtitle_browse)
            PageType.CART -> getString(R.string.subtitle_cart)
            PageType.CHECKOUT -> getString(R.string.subtitle_checkout)
            PageType.LOGIN -> getString(R.string.subtitle_login)
            PageType.REGISTER -> getString(R.string.subtitle_register)
            PageType.ACCOUNT -> getString(R.string.subtitle_account)
            PageType.STATIC -> getString(R.string.subtitle_static)
            PageType.HOME, PageType.UNKNOWN -> getString(R.string.subtitle_default)
        }
    }

    private fun updateCartBadge(count: Int) {
        val badge = binding.bottomNavView.getOrCreateBadge(R.id.nav_cart)
        badge.backgroundColor = ContextCompat.getColor(this, R.color.slp_filled)
        badge.badgeTextColor = ContextCompat.getColor(this, R.color.slp_on_filled)
        if (count > 0) {
            badge.number = count
            badge.isVisible = true
        } else {
            badge.isVisible = false
        }
    }

    private fun updateAuthMenuState(isLoggedIn: Boolean) {
        isUserLoggedIn = isLoggedIn

        val menu = binding.navigationView.menu
        menu.findItem(R.id.navigation_login)?.isVisible = !isLoggedIn
        menu.findItem(R.id.navigation_signup)?.isVisible = !isLoggedIn
        menu.findItem(R.id.navigation_account)?.isVisible = isLoggedIn
        menu.findItem(R.id.navigation_logout)?.isVisible = isLoggedIn

        binding.bottomNavView.menu.findItem(R.id.nav_account)?.title =
            getString(if (isLoggedIn) R.string.nav_account else R.string.nav_login)
    }

    // ── Toolbar ──

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        val item = menu.findItem(R.id.action_search)
        searchItem = item
        (item?.actionView as? SearchView)?.apply {
            queryHint = getString(R.string.search_hint)
            maxWidth = Int.MAX_VALUE
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    val q = query?.trim().orEmpty().take(MAX_SEARCH_CHARS)
                    if (q.isNotEmpty()) {
                        item.collapseActionView()
                        searchCatalog(q)
                    }
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean = false
            })
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_share -> { sharePage(); true }
        R.id.action_refresh -> { reloadPage(); true }
        R.id.action_open_in_browser -> { openExternally(currentPageUrl()); true }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Runs the site's own catalogue search. The search lives on the home
     * page: if that is where we are, inject.js exposes it as
     * `window.__slpSearch`; from anywhere else, load home with the query in
     * the fragment and inject.js picks it up there.
     */
    private fun searchCatalog(query: String) {
        // JSONObject.quote yields a valid JS string literal, so the query
        // cannot break out of it.
        val literal = JSONObject.quote(query)
        val js = "(function(){ if (typeof window.__slpSearch === 'function') {" +
            " window.__slpSearch($literal); return 'handled'; } return 'absent'; })();"
        binding.webView.evaluateJavascript(js) { result ->
            if (result == null || !result.contains("handled")) {
                binding.webView.loadUrl(
                    Storefront.HOME + "#" + Storefront.SEARCH_FRAGMENT_PREFIX + Uri.encode(query)
                )
            }
        }
    }

    private fun sharePage() {
        val url = currentPageUrl()
        val title = productTitle
            ?.takeIf { currentPageType == PageType.PRODUCT_DETAIL }
            ?: getString(R.string.app_name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        runCatching {
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
        }.onFailure { Log.w(TAG, "sharePage: no share target", it) }
    }

    private fun reloadPage() {
        if (mainFrameFailed) retryLoad() else binding.webView.reload()
    }

    // ── Drawer ──

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_login -> binding.webView.loadUrl(Storefront.SIGN_IN)
                R.id.navigation_signup -> binding.webView.loadUrl(Storefront.REGISTER)
                R.id.navigation_account -> binding.webView.loadUrl(accountUrl)
                R.id.navigation_logout -> logOut()
                R.id.navigation_payment_methods -> binding.webView.loadUrl("${Storefront.HOME}payment-methods")
                R.id.navigation_standing_order -> binding.webView.loadUrl("${Storefront.HOME}how-to-create-standing-order")
                R.id.navigation_downloads -> binding.webView.loadUrl("${Storefront.HOME}downloads")
                R.id.navigation_general_info -> binding.webView.loadUrl("${Storefront.HOME}general-infor")
                R.id.navigation_terms -> binding.webView.loadUrl("${Storefront.HOME}terms-and-conditions/")
                R.id.navigation_contact -> binding.webView.loadUrl("${Storefront.HOME}contact-us/")
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            // Drawer rows are actions, not destinations: leave none checked.
            false
        }
    }

    // ── WebView ──

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
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // These are already safe by default at this targetSdk, but pin
            // them so a future targetSdk change cannot silently reopen them.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        // Session persistence: accept cookies (site login is cookie-based) so
        // the signed-in session survives app restarts once flushed in onPause.
        CookieManager.getInstance().setAcceptCookie(true)

        binding.webView.addJavascriptInterface(contentBridge, BRIDGE_NAME)
        binding.webView.webViewClient = StorefrontWebViewClient()
        binding.webView.webChromeClient = StorefrontChromeClient()
    }

    private inner class StorefrontWebViewClient : WebViewClient() {

        /**
         * Default-deny. Only https on a trusted host stays in the WebView;
         * off-site http(s) is handed to the browser; every other scheme
         * (content://, intent://, blob:, file:, …) is dropped.
         */
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            val url = uri.toString()

            // www.stamps.slpost.gov.lk and http:// links are the same shop,
            // but neither loads here (bad certificate; cleartext is blocked).
            if (Storefront.needsCanonicalising(uri.scheme, uri.host)) {
                val fixed = uri.buildUpon().scheme("https").encodedAuthority(Storefront.HOST).build()
                view?.loadUrl(fixed.toString())
                return true
            }

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
            mainFrameFailed = false
            binding.progressBar.isVisible = true
            // A page may have suspended pull-to-refresh; the next one starts clean.
            binding.swipeRefreshLayout.isEnabled = true
            // The error screen, if showing, stays until the new page has
            // something to draw. Swapping to the WebView here flashed the
            // browser's own "Webpage not available" page on every retry.
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            if (mainFrameFailed) return
            // The new document exists but hasn't been drawn yet — injecting
            // here styles the page before the raw site is ever visible.
            injectCss(view)
            injectPageDetector(view)
            showContent()
            // Give the stylesheet a beat to apply before the curtain lifts.
            binding.loadingOverlay.postDelayed({ dismissSplash() }, 150)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            binding.swipeRefreshLayout.isRefreshing = false

            // The logout navigation has settled; the request carried the
            // session cookie, so it is now safe to clear it locally.
            clearLocalSession()

            if (mainFrameFailed) return

            // Re-inject CSS to override any late-loaded site styles, then the
            // page detector and the UX script in one evaluation so detection
            // has run before inject.js reads its result.
            injectCss(view)
            injectCombinedJs(view)
            syncBottomNavToUrl(url)
            showContent()
            dismissSplash()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame != true) return
            val code = error?.errorCode ?: ERROR_UNKNOWN
            val connectivity = code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT ||
                code == ERROR_TIMEOUT || code == ERROR_IO
            val failure = when {
                !isOnline() -> LoadFailure.OFFLINE
                connectivity -> LoadFailure.UNREACHABLE
                else -> LoadFailure.GENERIC
            }
            showErrorState(failure, error?.description?.toString())
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            // Without this a 5xx renders the raw server error page inside
            // the app chrome as though it were a normal screen.
            val status = errorResponse?.statusCode ?: return
            if (request?.isForMainFrame == true && status >= 500) {
                showErrorState(LoadFailure.SERVER, getString(R.string.error_http_status, status))
            }
        }

        /**
         * The system kills a WebView's renderer when memory is short, most
         * often while the app is in the background. Returning false here (the
         * default) crashes the app the next time it is opened. Throw the dead
         * WebView away and rebuild the Activity around a fresh one instead.
         */
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val crashed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail?.didCrash() == true
            Log.w(TAG, "WebView renderer gone (crashed=$crashed); recreating")
            if (!webViewDestroyed) {
                webViewDestroyed = true
                view?.let { web ->
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                }
            }
            if (!isFinishing && !isDestroyed) recreate()
            return true
        }
    }

    private inner class StorefrontChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            val bar = binding.progressBar
            // Animate forwards only: a new load starts back at a low value.
            bar.setProgressCompat(newProgress, newProgress > bar.progress)
            bar.isVisible = newProgress < 100
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

        // alert() and confirm() as app dialogs. The stock ones are titled
        // "The page at https://stamps.slpost.gov.lk says", which reads like
        // an error from somewhere else.

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
            showPageDialog(message, result, withCancel = false)

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
            showPageDialog(message, result, withCancel = true)
    }

    /**
     * Shows a dialog raised by page JavaScript. The result MUST be answered
     * exactly once on every path - an unanswered JsResult leaves the page's
     * JavaScript blocked for good.
     */
    private fun showPageDialog(message: String?, result: JsResult?, withCancel: Boolean): Boolean {
        if (result == null) return false
        if (isFinishing || isDestroyed) {
            result.cancel()
            return true
        }
        var answered = false
        val builder = MaterialAlertDialogBuilder(this)
            .setMessage(message.orEmpty().take(MAX_DIALOG_CHARS))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                answered = true
                result.confirm()
            }
            .setOnDismissListener {
                if (!answered) result.cancel()
            }
        if (withCancel) {
            builder.setNegativeButton(R.string.dialog_cancel) { _, _ ->
                answered = true
                result.cancel()
            }
        }
        return runCatching { builder.show(); true }.getOrElse {
            result.cancel()
            true
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.md_primary),
            ContextCompat.getColor(this, R.color.md_secondary)
        )
        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.md_surface_container_high)
        )
        binding.swipeRefreshLayout.setOnRefreshListener { binding.webView.reload() }
    }

    private fun setupBackButtonHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val search = searchItem
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    search != null && search.isActionViewExpanded -> search.collapseActionView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // ── Bottom bar ──

    private fun setupBottomNav() {
        binding.bottomNavView.setOnItemSelectedListener { item ->
            if (!syncingBottomNav) openTab(item.itemId)
            true
        }
        // Tapping the tab you are on: back to the top of its page, or back to
        // its page if you have wandered off it.
        binding.bottomNavView.setOnItemReselectedListener { item ->
            if (syncingBottomNav) return@setOnItemReselectedListener
            if (item.itemId != R.id.nav_browse && isAtTabRoot(item.itemId)) {
                binding.webView.evaluateJavascript(
                    "window.scrollTo({ top: 0, behavior: 'smooth' });", null
                )
            } else {
                openTab(item.itemId)
            }
        }
    }

    private fun openTab(itemId: Int) {
        when (itemId) {
            R.id.nav_home -> binding.webView.loadUrl(Storefront.HOME)
            R.id.nav_browse -> browseCatalog()
            R.id.nav_cart -> binding.webView.loadUrl(Storefront.CART)
            R.id.nav_account ->
                binding.webView.loadUrl(if (isUserLoggedIn) accountUrl else Storefront.SIGN_IN)
        }
    }

    private fun isAtTabRoot(itemId: Int): Boolean {
        if (mainFrameFailed) return false
        val here = binding.webView.url?.substringBefore('#')?.substringBefore('?')?.trimEnd('/') ?: return false
        val root = when (itemId) {
            R.id.nav_home -> Storefront.HOME
            R.id.nav_cart -> Storefront.CART
            R.id.nav_account -> if (isUserLoggedIn) accountUrl else Storefront.SIGN_IN
            else -> return false
        }
        return here == root.trimEnd('/')
    }

    /**
     * Browse = the product catalogue section on the home page. If we're already
     * there, scroll to it; otherwise load home with the #slp-browse fragment
     * that inject.js scrolls to after the page renders.
     */
    private fun browseCatalog() {
        val js = """(function(){
            if (location.pathname !== '/') return 'navigate';
            var t = document.querySelector('.category-tab, .nav-tabs, #resultPanel');
            if (!t) return 'navigate';
            t.scrollIntoView({ behavior: 'smooth', block: 'start' });
            return 'scrolled';
        })();"""
        binding.webView.evaluateJavascript(js) { result ->
            if (result == null || !result.contains("scrolled")) {
                binding.webView.loadUrl("${Storefront.HOME}#${Storefront.BROWSE_FRAGMENT}")
            }
        }
    }

    /** Highlights the tab the current page belongs to, without navigating. */
    private fun syncBottomNavToUrl(url: String?) {
        val uri = url?.takeIf { isStorefrontUrl(it) }?.toUri() ?: return
        val id = when (BottomTab.forLocation(uri.path, uri.fragment)) {
            BottomTab.HOME -> R.id.nav_home
            BottomTab.BROWSE -> R.id.nav_browse
            BottomTab.CART -> R.id.nav_cart
            BottomTab.ACCOUNT -> R.id.nav_account
        }
        if (binding.bottomNavView.selectedItemId == id) return
        syncingBottomNav = true
        try {
            binding.bottomNavView.selectedItemId = id
        } finally {
            syncingBottomNav = false
        }
    }

    // ── Loading and error states ──

    private fun dismissSplash(animate: Boolean = true) {
        if (splashDismissed) return
        splashDismissed = true
        val overlay = binding.loadingOverlay
        overlay.removeCallbacks(splashTimeout)
        if (!animate) {
            overlay.visibility = View.GONE
            return
        }
        overlay.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun showContent() {
        binding.errorView.visibility = View.GONE
        binding.swipeRefreshLayout.visibility = View.VISIBLE
        binding.retryButton.isEnabled = true
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Replaces the page with an explanation and a way to retry. Also lifts
     * the loading screen: on a failed first load nothing else would, and it
     * would sit on top of the retry button.
     */
    private fun showErrorState(failure: LoadFailure, detail: String?) {
        mainFrameFailed = true
        lastFailure = failure
        dismissSplash(animate = false)

        binding.errorIcon.setImageResource(failure.icon)
        binding.errorTitle.setText(failure.title)
        binding.errorMessage.setText(failure.message)
        binding.errorDetail.text = detail.orEmpty().take(MAX_TITLE_CHARS)
        binding.errorDetail.isVisible = !detail.isNullOrBlank()
        binding.retryButton.isEnabled = true

        binding.swipeRefreshLayout.visibility = View.GONE
        binding.swipeRefreshLayout.isRefreshing = false
        binding.errorView.visibility = View.VISIBLE
        binding.progressBar.isVisible = false
        clearLocalSession()
    }

    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener { retryLoad() }
    }

    private fun retryLoad() {
        if (webViewDestroyed) return
        // Disabled until the attempt resolves; onPageStarted shows progress.
        binding.retryButton.isEnabled = false
        binding.webView.reload()
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
        // Only download from Sri Lanka Post - a hostile page must not be able
        // to make the app fetch arbitrary hosts with the user's cookies.
        if (!isTrustedUrl(download.url)) {
            Log.w(TAG, "enqueueDownload: refusing untrusted URL")
            return
        }
        val fileName = URLUtil.guessFileName(
            download.url, download.contentDisposition, download.mimeType
        )
        val ok = runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
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
     * Navigating to /logout/ is not enough on its own: local state is reset
     * here rather than waiting on the bridge, and the cookie jar is cleared
     * once the navigation settles (in onPageFinished or on an error) so the
     * outbound request still carries the session cookie the server needs in
     * order to invalidate it.
     */
    private fun logOut() {
        pendingLogout = true
        binding.webView.loadUrl(Storefront.LOGOUT)
        accountUrl = Storefront.DEFAULT_ACCOUNT
        cartItemCount = 0
        prefs.edit { clear() }
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

    // ── Asset injection ──
    //
    // The three scripts are built once and reused. They used to be read from
    // the APK and re-encoded on every page - twice per page for the
    // stylesheet - on the UI thread.

    private val isDebuggable: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Installs inject.css and marks <html> with the current theme.
     *
     * The stylesheet travels as Base64 so that no character in it can break
     * the surrounding JS string literal.
     */
    private val cssInjectorJs: String? by lazy {
        val css = readAsset("inject.css") ?: return@lazy null
        val b64 = Base64.encodeToString(css.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        """(function(){
            try {
                document.documentElement.classList.toggle('slp-dark', ${isNightMode()});
                var existing = document.getElementById('slp-injected-css');
                if (existing && existing.getAttribute('data-len') === '${b64.length}') {
                    // Already installed for this document: move it back to the
                    // end so it still follows any stylesheet added since.
                    existing.parentNode.appendChild(existing);
                    return 'css-kept';
                }
                var css = new TextDecoder('utf-8').decode(
                    Uint8Array.from(atob('$b64'), function(c){ return c.charCodeAt(0); }));
                if (existing) { existing.remove(); }
                var s = document.createElement('style');
                s.id = 'slp-injected-css';
                s.setAttribute('data-len', '${b64.length}');
                s.textContent = css;
                (document.head || document.documentElement).appendChild(s);
                return 'css-ok';
            } catch (e) { return 'css-error: ' + e.message; }
        })();"""
    }

    private val pageDetectorJs: String? by lazy {
        readAsset("page-detector.js")?.let { wrapForLogging(it, "page-detector") }
    }

    /**
     * page-detector.js and inject.js in a single evaluation, so that page
     * detection has set window.__SLP_PAGE_TYPE__ before inject.js reads it —
     * two separate evaluateJavascript calls give no such ordering.
     */
    private val combinedJs: String? by lazy {
        val detector = readAsset("page-detector.js") ?: return@lazy null
        val inject = readAsset("inject.js") ?: return@lazy null
        // inject.js can run a getComputedStyle sweep over the whole page and
        // log one bridge message per full-viewport element. Useful for chasing
        // a blank screen, far too chatty for release - and it shares the
        // bridge-call budget with the cart and auth callbacks. Debug only.
        val prelude = "window.__SLP_DIAG__ = $isDebuggable;\n"
        wrapForLogging("$prelude$detector\n$inject", "combined")
    }

    /** Our CSS and JS go into the storefront's pages and nobody else's. */
    private fun canInject(view: WebView?): Boolean = view != null && isStorefrontUrl(view.url)

    private fun injectCss(view: WebView?) {
        if (!canInject(view)) return
        val js = cssInjectorJs ?: return
        view?.evaluateJavascript(js) { result ->
            if (result?.contains("error") == true) Log.w(TAG, "injectCss: $result")
        }
    }

    /**
     * Runs page-detector.js at first paint (onPageCommitVisible) so the body
     * class (slp-page-*) is set before the user sees the page.
     */
    private fun injectPageDetector(view: WebView?) {
        if (!canInject(view)) return
        val js = pageDetectorJs ?: return
        view?.evaluateJavascript(js, null)
    }

    private fun injectCombinedJs(view: WebView?) {
        if (!canInject(view)) return
        val js = combinedJs ?: return
        view?.evaluateJavascript(js) { result ->
            if (result?.contains("error") == true) Log.w(TAG, "injectCombinedJs: $result")
        }
    }

    /**
     * Wraps a script in an IIFE with a try/catch so failures surface in
     * logcat (tag MainActivity / WebContentBridge) instead of dying silently.
     */
    private fun wrapForLogging(script: String, label: String): String = """(function(){
            try {
                $script
                return '$label-ok';
            } catch (e) {
                if (window.SLPBridge && SLPBridge.log) { SLPBridge.log('$label error: ' + e.message); }
                return '$label-error: ' + e.message;
            }
        })();"""

    private fun readAsset(filename: String): String? =
        runCatching { assets.open(filename).bufferedReader().use { it.readText() } }
            .onFailure { Log.w(TAG, "readAsset: $filename missing", it) }
            .getOrNull()
}
