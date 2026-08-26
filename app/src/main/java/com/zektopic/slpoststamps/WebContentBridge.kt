package com.zektopic.slpoststamps

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Bridge exposed to page JavaScript as `SLPBridge`.
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
    private val productViewedCallback: (title: String, price: String, imageUrl: String) -> Unit
) {
    companion object {
        private const val TAG = "WebContentBridge"
    }

    @JavascriptInterface
    fun onPageDetected(pageType: String, metadata: String) {
        val type = PageType.fromString(pageType)
        Log.d(TAG, "Page detected: $type, metadata: $metadata")
        pageDetectedCallback(type, metadata)
    }

    @JavascriptInterface
    fun onAuthStateChanged(isLoggedIn: Boolean, accountUrl: String) {
        Log.d(TAG, "Auth state changed: loggedIn=$isLoggedIn, url=$accountUrl")
        authStateChangedCallback(isLoggedIn, accountUrl)
    }

    @JavascriptInterface
    fun onCartUpdated(itemCount: Int) {
        Log.d(TAG, "Cart updated: $itemCount items")
        cartUpdatedCallback(itemCount)
    }

    @JavascriptInterface
    fun onProductViewed(title: String, price: String, imageUrl: String) {
        Log.d(TAG, "Product viewed: $title, $price")
        productViewedCallback(title, price, imageUrl)
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "JS: $message")
    }
}
