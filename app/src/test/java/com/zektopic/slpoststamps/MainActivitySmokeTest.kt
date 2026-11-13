package com.zektopic.slpoststamps

import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Starts the real Activity on the JVM. There is no network and no page here -
 * Robolectric's WebView records calls rather than rendering - so this covers
 * the app's own chrome: that the layouts, menus and themes inflate, in both
 * themes, and that the load-failure screen appears and clears.
 *
 * A typo in a layout attribute compiles and only fails when the Activity is
 * launched; without this test that means on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySmokeTest {

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun mainFrame(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun launchesAndLoadsTheStorefront() {
        val activity = launch()
        val webView = activity.findViewById<WebView>(R.id.webView)
        assertEquals(Storefront.HOME, shadowOf(webView).lastLoadedUrl)

        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
        assertEquals(activity.getString(R.string.app_name), toolbar.title.toString())
        assertEquals(activity.getString(R.string.subtitle_default), toolbar.subtitle.toString())
    }

    @Test
    fun bottomBarHasFourTabsAndStartsSignedOut() {
        val activity = launch()
        val bar = activity.findViewById<BottomNavigationView>(R.id.bottomNavView)
        assertEquals(4, bar.menu.size())
        assertEquals(R.id.nav_home, bar.selectedItemId)
        assertEquals(
            activity.getString(R.string.nav_login),
            bar.menu.findItem(R.id.nav_account).title.toString()
        )
    }

    @Test
    fun drawerShowsGuestActionsWhenSignedOut() {
        val menu = launch().findViewById<NavigationView>(R.id.navigationView).menu
        assertTrue(menu.findItem(R.id.navigation_login).isVisible)
        assertTrue(menu.findItem(R.id.navigation_signup).isVisible)
        assertFalse(menu.findItem(R.id.navigation_account).isVisible)
        assertFalse(menu.findItem(R.id.navigation_logout).isVisible)
    }

    @Test
    fun toolbarOffersSearchShareRefreshAndOpenInBrowser() {
        val toolbar = launch().findViewById<MaterialToolbar>(R.id.toolbar)
        listOf(
            R.id.action_search, R.id.action_share,
            R.id.action_refresh, R.id.action_open_in_browser
        ).forEach { assertNotNull(toolbar.menu.findItem(it)) }
        assertNotNull(toolbar.menu.findItem(R.id.action_search).actionView)
    }

    @Test
    fun serverErrorShowsTheErrorScreenAndASuccessfulLoadClearsIt() {
        val activity = launch()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val client = shadowOf(webView).webViewClient
        val errorView = activity.findViewById<View>(R.id.errorView)
        val content = activity.findViewById<View>(R.id.swipeRefreshLayout)

        client.onPageStarted(webView, Storefront.HOME, null)
        client.onReceivedHttpError(
            webView, mainFrame(Storefront.HOME),
            WebResourceResponse("text/html", "utf-8", 503, "Service Unavailable", emptyMap(), null)
        )
        assertEquals(View.VISIBLE, errorView.visibility)
        assertEquals(View.GONE, content.visibility)
        assertEquals(
            activity.getString(R.string.error_server_title),
            activity.findViewById<TextView>(R.id.errorTitle).text.toString()
        )
        assertEquals("HTTP 503", activity.findViewById<TextView>(R.id.errorDetail).text.toString())

        // The failed load still "finishes"; that must not bring the page back.
        client.onPageFinished(webView, Storefront.HOME)
        assertEquals(View.VISIBLE, errorView.visibility)

        // Retry: the error screen stays up until the new page can draw.
        client.onPageStarted(webView, Storefront.HOME, null)
        assertEquals(View.VISIBLE, errorView.visibility)
        client.onPageCommitVisible(webView, Storefront.HOME)
        assertEquals(View.GONE, errorView.visibility)
        assertEquals(View.VISIBLE, content.visibility)
    }

    @Test
    fun subresourceErrorsDoNotReplaceThePage() {
        val activity = launch()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val request = object : WebResourceRequest by mainFrame("https://stapp.slpost.gov.lk/x.jpg") {
            override fun isForMainFrame() = false
        }
        shadowOf(webView).webViewClient.onReceivedHttpError(
            webView, request,
            WebResourceResponse("text/html", "utf-8", 500, "Server Error", emptyMap(), null)
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.errorView).visibility)
    }

    @Test
    fun navigationPolicy_keepsTheShopInAppAndRewritesItsBrokenAlias() {
        val activity = launch()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val client = shadowOf(webView).webViewClient

        // On the storefront: stays in the WebView.
        assertFalse(client.shouldOverrideUrlLoading(webView, mainFrame("https://stamps.slpost.gov.lk/product/1")))

        // The www alias has a bad certificate: reloaded on the working host.
        assertTrue(client.shouldOverrideUrlLoading(webView, mainFrame("https://www.stamps.slpost.gov.lk/product/6758?a=1")))
        assertEquals("https://stamps.slpost.gov.lk/product/6758?a=1", shadowOf(webView).lastLoadedUrl)

        // A lookalike host must not be loaded in the WebView.
        assertTrue(client.shouldOverrideUrlLoading(webView, mainFrame("https://stamps.slpost.gov.lk.evil.test/")))
        assertEquals("https://stamps.slpost.gov.lk/product/6758?a=1", shadowOf(webView).lastLoadedUrl)

        // Unknown schemes are dropped, not rendered.
        assertTrue(client.shouldOverrideUrlLoading(webView, mainFrame("intent://scan/#Intent;end")))
    }

    @Test
    @Config(qualifiers = "night")
    fun launchesInTheDarkTheme() {
        val activity = launch()
        assertNotNull(activity.findViewById<BottomNavigationView>(R.id.bottomNavView))
    }

    @Test
    fun survivesRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.recreate()
        assertNotNull(controller.get().findViewById<WebView>(R.id.webView))
    }
}
