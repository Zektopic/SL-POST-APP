package com.zektopic.slpoststamps

enum class PageType {
    HOME,
    PRODUCT_LISTING,
    PRODUCT_DETAIL,
    CART,
    CHECKOUT,
    LOGIN,
    REGISTER,
    ACCOUNT,
    STATIC,
    UNKNOWN;

    companion object {
        fun fromString(value: String): PageType {
            return try {
                valueOf(value.uppercase())
            } catch (_: IllegalArgumentException) {
                UNKNOWN
            }
        }
    }
}
