package com.zektopic.slpoststamps

/**
 * The storefront's addresses and the rules about which hosts the app treats
 * as "the site".
 *
 * Free of Android types on purpose, so the rules can be unit tested on the JVM.
 * Callers pass in the host / path / fragment they parsed from a URL.
 */
internal object Storefront {

    const val HOST = "stamps.slpost.gov.lk"
    private const val ALIAS_HOST = "www.$HOST"
    private const val DOMAIN_SUFFIX = ".slpost.gov.lk"

    const val HOME = "https://$HOST/"
    const val CART = "https://$HOST/cart-view"

    /**
     * The dedicated sign-in page. /login also has a sign-in form, but only in
     * the site header, which the app hides; that page is the register form.
     */
    const val SIGN_IN = "https://$HOST/users/login-view"

    /**
     * GET /users/register returns an empty page: registration is an inline
     * block (#registetModal - the site's spelling) on /login.
     */
    const val REGISTER = "https://$HOST/login#registetModal"
    const val LOGOUT = "https://$HOST/logout/"
    const val DEFAULT_ACCOUNT = "https://$HOST/users/"

    /** Fragment the Browse tab uses; inject.js scrolls to the catalogue for it. */
    const val BROWSE_FRAGMENT = "slp-browse"

    /** Fragment prefix carrying a toolbar search to the home page. */
    const val SEARCH_FRAGMENT_PREFIX = "slp-search="

    /** The storefront itself - the only host the app injects CSS and JS into. */
    fun isStorefrontHost(host: String?): Boolean = host?.lowercase() == HOST

    /**
     * The storefront or another Sri Lanka Post host; these stay in the WebView.
     *
     * Suffix matching, never substring matching: `contains("slpost.gov.lk")`
     * would also accept `slpost.gov.lk.example.com`.
     */
    fun isTrustedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == HOST || h.endsWith(DOMAIN_SUFFIX)
    }

    /**
     * True when a link to [host] over [scheme] should be rewritten to
     * `https://stamps.slpost.gov.lk` before it is loaded.
     *
     * The site's own slides link to `www.stamps.slpost.gov.lk`, whose
     * certificate does not cover that name, so the link fails to load; and a
     * plain `http://` link to the storefront is blocked by the app's
     * no-cleartext policy. Both are the same site as far as the user is
     * concerned.
     */
    fun needsCanonicalising(scheme: String?, host: String?): Boolean {
        val s = scheme?.lowercase()
        val h = host?.lowercase()
        if (s != "http" && s != "https") return false
        if (h != HOST && h != ALIAS_HOST) return false
        return !(s == "https" && h == HOST)
    }
}

/** The bottom bar's destinations, and which one a storefront page belongs to. */
internal enum class BottomTab {
    HOME, BROWSE, CART, ACCOUNT;

    companion object {
        fun forLocation(path: String?, fragment: String?): BottomTab {
            val p = (path ?: "/").lowercase()
            return when {
                p.startsWith("/cart") || p.startsWith("/checkout") -> CART
                p.startsWith("/login") || p.startsWith("/users") -> ACCOUNT
                p.startsWith("/product") -> BROWSE
                (p == "/" || p.isEmpty()) && fragment == Storefront.BROWSE_FRAGMENT -> BROWSE
                else -> HOME
            }
        }
    }
}
