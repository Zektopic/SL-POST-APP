package com.zektopic.slpoststamps

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

class WebContentBridge(
    private val onPageDetected: (PageType, String) -> Unit,
    private val onAuthStateChanged: (Boolean, String) -> Unit,
    private val onCartUpdated: (Int) -> Unit,
    private val onProductViewed: (title: String, price: String, imageUrl: String) -> Unit
) {
    companion object {
        private const val TAG = "WebContentBridge"
    }

    @JavascriptInterface
    fun onPageDetected(pageType: String, metadata: String) {
        val type = PageType.fromString(pageType)
        Log.d(TAG, "Page detected: $type, metadata: $metadata")
        onPageDetected(type, metadata)
    }

    @JavascriptInterface
    fun onAuthStateChanged(isLoggedIn: Boolean, accountUrl: String) {
        Log.d(TAG, "Auth state changed: loggedIn=$isLoggedIn, url=$accountUrl")
        onAuthStateChanged(isLoggedIn, accountUrl)
    }

    @JavascriptInterface
    fun onCartUpdated(itemCount: Int) {
        Log.d(TAG, "Cart updated: $itemCount items")
        onCartUpdated(itemCount)
    }

    @JavascriptInterface
    fun onProductViewed(title: String, price: String, imageUrl: String) {
        Log.d(TAG, "Product viewed: $title, $price")
        onProductViewed(title, price, imageUrl)
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "JS: $message")
    }
}
