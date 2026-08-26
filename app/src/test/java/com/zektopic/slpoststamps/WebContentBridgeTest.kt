package com.zektopic.slpoststamps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bridge callback wiring.
 *
 * Before the `…Callback` rename, each constructor property shared its name and
 * signature with the `@JavascriptInterface` method that forwarded to it. Kotlin
 * resolves member functions ahead of properties-with-`invoke`, so those methods
 * called themselves and died with StackOverflowError on the JavaBridge thread —
 * silently disabling the cart badge, auth detection and product title.
 *
 * Each test below fails with StackOverflowError if that regression returns.
 */
class WebContentBridgeTest {

    private fun bridge(
        onPage: (PageType, String) -> Unit = { _, _ -> },
        onAuth: (Boolean, String) -> Unit = { _, _ -> },
        onCart: (Int) -> Unit = {},
        onProduct: (String, String, String) -> Unit = { _, _, _ -> }
    ) = WebContentBridge(onPage, onAuth, onCart, onProduct)

    @Test
    fun onCartUpdated_reachesCallback() {
        var received = -1
        bridge(onCart = { received = it }).onCartUpdated(3)
        assertEquals(3, received)
    }

    @Test
    fun onAuthStateChanged_reachesCallback() {
        var loggedIn = false
        var url = ""
        bridge(onAuth = { l, u -> loggedIn = l; url = u })
            .onAuthStateChanged(true, "https://stamps.slpost.gov.lk/users/view/1")
        assertTrue(loggedIn)
        assertEquals("https://stamps.slpost.gov.lk/users/view/1", url)
    }

    @Test
    fun onProductViewed_reachesCallback() {
        var title = ""
        bridge(onProduct = { t, _, _ -> title = t }).onProductViewed("Lotus Tower", "Rs 50", "")
        assertEquals("Lotus Tower", title)
    }

    @Test
    fun onPageDetected_reachesCallbackWithParsedType() {
        var type: PageType? = null
        bridge(onPage = { t, _ -> type = t }).onPageDetected("CART", "{}")
        assertEquals(PageType.CART, type)
    }

    @Test
    fun onPageDetected_unknownStringFallsBackToUnknown() {
        var type: PageType? = null
        bridge(onPage = { t, _ -> type = t }).onPageDetected("not-a-page-type", "{}")
        assertEquals(PageType.UNKNOWN, type)
    }
}
