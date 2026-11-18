package com.carlos.bloqueador

import android.content.Context

/** Guarda las apps bloqueadas y la hora de fin del temporizador. */
object BlockStore {
    private const val PREFS = "bloqueador"
    private const val KEY_APPS = "apps"
    private const val KEY_END = "end_millis"

    fun start(ctx: Context, packages: Set<String>, minutes: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_APPS, packages)
            .putLong(KEY_END, System.currentTimeMillis() + minutes * 60_000L)
            .apply()
    }

    fun endMillis(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_END, 0L)

    fun isActive(ctx: Context): Boolean = System.currentTimeMillis() < endMillis(ctx)

    fun remainingMinutes(ctx: Context): Long =
        ((endMillis(ctx) - System.currentTimeMillis()) / 60_000L) + 1

    fun isBlocked(ctx: Context, pkg: String): Boolean {
        if (!isActive(ctx)) return false
        val apps = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_APPS, emptySet()) ?: emptySet()
        return pkg in apps
    }
}
