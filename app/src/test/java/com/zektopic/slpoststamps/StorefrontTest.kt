package com.zektopic.slpoststamps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorefrontTest {

    // ── hosts ──

    @Test
    fun storefrontHostIsExactAndCaseInsensitive() {
        assertTrue(Storefront.isStorefrontHost("stamps.slpost.gov.lk"))
        assertTrue(Storefront.isStorefrontHost("STAMPS.SLPOST.GOV.LK"))
        assertFalse(Storefront.isStorefrontHost("www.stamps.slpost.gov.lk"))
        assertFalse(Storefront.isStorefrontHost("stapp.slpost.gov.lk"))
        assertFalse(Storefront.isStorefrontHost(null))
    }

    @Test
    fun trustedHostsAreTheStorefrontAndItsSiblings() {
        assertTrue(Storefront.isTrustedHost("stamps.slpost.gov.lk"))
        assertTrue(Storefront.isTrustedHost("stapp.slpost.gov.lk"))
        assertTrue(Storefront.isTrustedHost("www.stamps.slpost.gov.lk"))
    }

    @Test
    fun lookalikeHostsAreNotTrusted() {
        // Substring matching would accept every one of these.
        listOf(
            "slpost.gov.lk.example.com",
            "stamps.slpost.gov.lk.evil.test",
            "evilslpost.gov.lk",
            "slpost.gov.lk",
            "example.com",
            ""
        ).forEach { assertFalse(it, Storefront.isTrustedHost(it)) }
        assertFalse(Storefront.isTrustedHost(null))
    }

    // ── canonical host ──

    @Test
    fun canonicalStorefrontUrlIsLeftAlone() {
        assertFalse(Storefront.needsCanonicalising("https", "stamps.slpost.gov.lk"))
    }

    @Test
    fun wwwAliasIsRewritten() {
        // The site's own slides link here; the certificate does not cover it.
        assertTrue(Storefront.needsCanonicalising("https", "www.stamps.slpost.gov.lk"))
        assertTrue(Storefront.needsCanonicalising("HTTPS", "WWW.Stamps.SLPost.gov.lk"))
    }

    @Test
    fun cleartextStorefrontLinkIsUpgraded() {
        assertTrue(Storefront.needsCanonicalising("http", "stamps.slpost.gov.lk"))
        assertTrue(Storefront.needsCanonicalising("http", "www.stamps.slpost.gov.lk"))
    }

    @Test
    fun otherHostsAndSchemesAreNeverRewritten() {
        assertFalse(Storefront.needsCanonicalising("https", "stapp.slpost.gov.lk"))
        assertFalse(Storefront.needsCanonicalising("https", "example.com"))
        assertFalse(Storefront.needsCanonicalising("intent", "stamps.slpost.gov.lk"))
        assertFalse(Storefront.needsCanonicalising(null, "stamps.slpost.gov.lk"))
        assertFalse(Storefront.needsCanonicalising("https", null))
    }

    // ── bottom bar ──

    @Test
    fun cartPagesSelectCart() {
        assertEquals(BottomTab.CART, BottomTab.forLocation("/cart-view", null))
        assertEquals(BottomTab.CART, BottomTab.forLocation("/checkout", null))
    }

    @Test
    fun signInAndAccountPagesSelectAccount() {
        listOf("/login", "/users/login-view", "/users/password", "/users/view/12")
            .forEach { assertEquals(it, BottomTab.ACCOUNT, BottomTab.forLocation(it, null)) }
    }

    @Test
    fun productPagesAndTheCatalogueAnchorSelectBrowse() {
        assertEquals(BottomTab.BROWSE, BottomTab.forLocation("/product/6577", null))
        assertEquals(BottomTab.BROWSE, BottomTab.forLocation("/", Storefront.BROWSE_FRAGMENT))
    }

    @Test
    fun everythingElseSelectsHome() {
        assertEquals(BottomTab.HOME, BottomTab.forLocation("/", null))
        assertEquals(BottomTab.HOME, BottomTab.forLocation(null, null))
        assertEquals(BottomTab.HOME, BottomTab.forLocation("/contact-us/", null))
        // The add-to-cart sheet's fragment on the home page is not Browse.
        assertEquals(BottomTab.HOME, BottomTab.forLocation("/", "model_6957"))
    }
}
