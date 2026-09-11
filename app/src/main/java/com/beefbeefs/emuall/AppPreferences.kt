package com.beefbeefs.emuall

import android.content.Context

/** Small app-wide preferences surfaced from the home screen. */
object AppPreferences {
    private const val NAME = "emuall_preferences"
    private const val PREFER_VULKAN = "prefer_vulkan"
    private const val KEEP_SCREEN_ON = "keep_screen_on"

    fun preferVulkan(context: Context): Boolean = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(PREFER_VULKAN, true)

    fun setPreferVulkan(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFER_VULKAN, value)
            .apply()
    }

    fun keepScreenOn(context: Context): Boolean = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEEP_SCREEN_ON, value)
            .apply()
    }
}
