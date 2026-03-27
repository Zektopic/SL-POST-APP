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
import androidx.core.view.GravityCompat
import com.zektopic.slpoststamps.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TARGET_URL = "https://stamps.slpost.gov.lk/"
    private val TARGET_DOMAIN = "slpost.gov.lk" // Broader domain catch

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set Toolbar as Action Bar for the Drawer Sidebar toggle icon handling
        setSupportActionBar(binding.toolbar)

        setupDrawer()
        setupWebView()
        setupSwipeRefresh()
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
                    // Navigate to Home
                    binding.webView.loadUrl(TARGET_URL)
                }
                R.id.navigation_shop -> {
                    // Navigate to the categories hash or just the base url (which is the shop)
                    binding.webView.loadUrl(TARGET_URL)
                }
                R.id.navigation_cart -> {
                    // Navigate to cart URL or trigger the native view-cart icon via javascript
                    val triggerCartJS = "document.querySelector('.view-cart-button')?.click();"
                    binding.webView.evaluateJavascript(triggerCartJS, null)
                }
            }
            // Close sidebar after tapping an item
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun setupWebView() {
        // Enable necessary settings for modern rendering and functions
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // Essential for the shopping cart and modern JS frameworks
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false // Hide the clunky native zoom buttons
            
            // Fix for Android blank white screens due to mixed external assets and APIs
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
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
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Stop pull-to-refresh spinner
                binding.swipeRefreshLayout.isRefreshing = false
                
                // Inject Custom CSS safely via single-line escaped strings to prevent Webkit Syntax breaking the screen
                val materialCSS = """
                    #header, #footer { display: none !important; }
                    /* Material UI overrides for Auth Forms */
                    #loginModal .modal-content, #registetModal .modal-content, .modal-content.boxshadownone {
                        border-radius: 16px !important; border: none !important;
                        box-shadow: 0 4px 24px rgba(0,0,0,0.08) !important;
                        padding: 24px !important; margin: 16px auto !important;
                        background: #ffffff !important;
                    }
                    .modal-header { border-bottom: none !important; text-align: center !important; padding-top: 0 !important; }
                    .modal-title { font-family: sans-serif !important; font-weight: 600 !important; color: #212121 !important; font-size: 26px !important; margin-bottom: 8px !important; }
                    .input, select.form-control, input.form-control {
                        width: 100% !important; padding: 14px 16px !important; margin-bottom: 16px !important;
                        border: 1px solid #e0e0e0 !important; border-radius: 8px !important;
                        box-sizing: border-box !important; font-size: 16px !important;
                        background-color: #fafafa !important; outline: none !important;
                        color: #212121 !important;
                    }
                    .input:focus, select.form-control:focus, input.form-control:focus { border-color: #6200EE !important; background-color: #fff !important; }
                    #login-btn, input.button.raised.blue {
                        background-color: #6200EE !important; color: white !important; border: none !important;
                        border-radius: 24px !important; padding: 14px 24px !important; font-size: 16px !important;
                        font-weight: bold !important; letter-spacing: 0.5px !important; text-transform: uppercase !important;
                        width: 100% !important; margin-top: 16px !important; margin-bottom: 16px !important;
                        box-shadow: 0 4px 10px rgba(98, 0, 238, 0.3) !important; appearance: none !important;
                    }
                    .register-now { text-align: center !important; font-size: 14px !important; margin-top: 10px !important; color: #757575 !important; }
                    .register-now a { color: #6200EE !important; text-decoration: none !important; font-weight: bold !important; }
                    .terms-and-conditions .dec-section {
                        border-radius: 8px !important; border: 1px solid #eeeeee !important;
                        background: #fafafa !important; padding: 12px !important; margin-bottom: 16px !important;
                    }
                    #terms-and-conditions-heading h4 { font-size: 14px !important; font-weight: bold !important; color: #424242 !important; }
                    label[for="remember_me"], label[for="agree"] { font-size: 14px !important; color: #616161 !important; font-weight: normal !important; margin-left: 8px !important; vertical-align: middle !important; }
                    input[type="checkbox"] { width: 18px !important; height: 18px !important; accent-color: #6200EE !important; vertical-align: middle !important; }
                """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

                val cssOverride = """
                    var style = document.createElement('style');
                    style.innerHTML = "$materialCSS";
                    document.head.appendChild(style);
                """.trimIndent()
                
                view?.evaluateJavascript(
                    "(function() { $cssOverride })();",
                    null
                )
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
                // Tie progress visually to horizontal bar
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        // Standard pull-to-refresh
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
    
    private fun setupRetryButton() {
        // Error screen retry logic
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.swipeRefreshLayout.visibility = View.VISIBLE
            binding.webView.reload()
        }
    }
}