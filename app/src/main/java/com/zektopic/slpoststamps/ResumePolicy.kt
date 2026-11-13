package com.zektopic.slpoststamps

/**
 * Decides whether a storefront path is safe to reopen on a cold start.
 *
 * Kept free of Android types so it can be covered by plain JVM unit tests.
 * Host and scheme checks stay in MainActivity; this only judges the path.
 */
internal object ResumePolicy {

    /**
     * Path fragments that must not be reopened on a cold start.
     *
     * The first group performs an action on GET - the site's real routes are
     * `/remove-from-cart/<id>/` and `/clear-cart` - so replaying one would
     * silently repeat it. The second group is only ever reached by a form
     * POST; reopening it as a GET lands on an error or a login redirect
     * instead of where the user actually was.
     */
    private val NON_RESUMABLE_PATHS = listOf(
        // state-changing GETs
        "/add-to-cart", "/cart-add", "/remove", "/delete", "/clear-cart", "/logout",
        // POST-only results
        "/update-cart", "/checkout", "/reload-view", "/check-order-status", "/search"
    )

    fun isResumablePath(path: String?): Boolean {
        val p = path?.lowercase() ?: return false
        return NON_RESUMABLE_PATHS.none { p.contains(it) }
    }
}
