package com.zektopic.slpoststamps

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Bridge exposed to page JavaScript as `SLPBridge`.
 *
 * Everything that arrives here is untrusted: the page belongs to a third
 * party, and any script on it can call these methods. Callbacks receive the
 * raw values and are responsible for validating them. Every method is invoked
 * on the WebView's JavaBridge thread, never the UI thread.
 *
 * The callback properties deliberately do NOT share a name with the
 * `@JavascriptInterface` methods below. Kotlin resolves a bare `foo(x)` call
 * against member *functions* before properties-with-`invoke`, so naming a
 * constructor property the same as the method that forwards to it makes the
 * method call itself instead — an unbounded recursion that dies with
 * StackOverflowError on the JavaBridge thread. Keep the `…Callback` suffix.
 */
class WebContentBridge(
    private val pageDetectedCallback: (PageType, String) -> Unit,
    private val authStateChangedCallback: (Boolean, String) -> Unit,
    private val cartUpdatedCallback: (Int) -> Unit,
    private val productViewedCallback: (title: String, price: String, imageUrl: String) -> Unit,
    private val pullToRefreshCallback: (enabled: Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "WebContentBridge"
    }

    @JavascriptInterface
    fun onPageDetected(pageType: String, metadata: String) {
        val type = PageType.fromString(pageType)
        Log.d(TAG, "Page detected: $type")
        pageDetectedCallback(type, metadata)
    }

    @JavascriptInterface
    fun onAuthStateChanged(isLoggedIn: Boolean, accountUrl: String) {
        Log.d(TAG, "Auth state changed: loggedIn=$isLoggedIn")
        authStateChangedCallback(isLoggedIn, accountUrl)
    }

    @JavascriptInterface
    fun onCartUpdated(itemCount: Int) {
        Log.d(TAG, "Cart updated: $itemCount items")
        cartUpdatedCallback(itemCount)
    }

    @JavascriptInterface
    fun onProductViewed(title: String, price: String, imageUrl: String) {
        productViewedCallback(title, price, imageUrl)
    }

    /**
     * The page asks for pull-to-refresh to be suspended while it shows
     * something of its own that is dragged vertically (the image viewer, the
     * add-to-cart sheet). Without this, dragging down inside one of those
     * reloads the page underneath it.
     */
    @JavascriptInterface
    fun setPullToRefreshEnabled(enabled: Boolean) {
        pullToRefreshCallback(enabled)
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "JS: $message")
    }
}
