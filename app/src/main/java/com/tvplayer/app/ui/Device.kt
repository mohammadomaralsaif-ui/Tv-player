package com.tvplayer.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Where the app is running: a TV / TV box (remote control) or a phone / tablet (touch). */
object Device {
    private var tvCache: Boolean? = null

    fun isTv(ctx: Context): Boolean = tvCache ?: run {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val pm = ctx.packageManager
        (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)).also { tvCache = it }
    }

    fun isPortrait(ctx: Context): Boolean =
        ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** Phone held upright: categories go on top as chips instead of a side column. */
    fun isNarrow(ctx: Context): Boolean = !isTv(ctx) && ctx.resources.configuration.screenWidthDp < 600

    fun widthDp(ctx: Context): Int = ctx.resources.configuration.screenWidthDp
}
