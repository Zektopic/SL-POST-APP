package com.zektopic.slpoststamps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun ordinaryPagesAreResumable() {
        listOf("/", "/product/6577", "/cart-view", "/login", "/users/view/12", "/contact-us/")
            .forEach { assertTrue(it, ResumePolicy.isResumablePath(it)) }
    }

    @Test
    fun stateChangingGetsAreNotResumable() {
        // Real routes on the storefront: reopening either would repeat the action.
        listOf("/remove-from-cart/7105/", "/clear-cart", "/logout/", "/add-to-cart")
            .forEach { assertFalse(it, ResumePolicy.isResumablePath(it)) }
    }

    @Test
    fun postOnlyResultsAreNotResumable() {
        listOf("/checkout", "/update-cart", "/reload-view", "/check-order-status", "/search")
            .forEach { assertFalse(it, ResumePolicy.isResumablePath(it)) }
    }

    @Test
    fun matchingIgnoresCase() {
        assertFalse(ResumePolicy.isResumablePath("/Clear-Cart"))
    }

    @Test
    fun missingPathIsNotResumable() {
        assertFalse(ResumePolicy.isResumablePath(null))
    }
}
