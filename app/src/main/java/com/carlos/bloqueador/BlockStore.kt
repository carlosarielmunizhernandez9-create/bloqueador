package com.carlos.bloqueador

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Guarda las apps bloqueadas y el temporizador, a prueba de cambios de hora. */
object BlockStore {
    private const val PREFS = "bloqueador"
    private const val KEY_APPS = "apps"
    private const val KEY_END_WALL = "end_millis"
    private const val KEY_END_ELAPSED = "end_elapsed"
    private const val KEY_BOOT = "boot_count"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bootCount(ctx: Context): Int =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun start(ctx: Context, packages: Set<String>, minutes: Int) {
        val ms = minutes * 60_000L
        prefs(ctx).edit()
            .putStringSet(KEY_APPS, packages)
            .putLong(KEY_END_WALL, System.currentTimeMillis() + ms)
            .putLong(KEY_END_ELAPSED, SystemClock.elapsedRealtime() + ms)
            .putInt(KEY_BOOT, bootCount(ctx))
            .apply()
    }

    /** Milisegundos que faltan. Usa el reloj interno, que no cambia al mover la hora. */
    fun remainingMs(ctx: Context): Long {
        val p = prefs(ctx)
        val endWall = p.getLong(KEY_END_WALL, 0L)
        if (endWall == 0L) return 0L

        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val sameBoot = p.getInt(KEY_BOOT, -1) == bootCount(ctx)

        val remaining = if (sameBoot) {
            p.getLong(KEY_END_ELAPSED, 0L) - nowElapsed
        } else {
            endWall - nowWall // hubo reinicio: se usa la hora normal
        }
        if (remaining <= 0L) return 0L

        // Resincroniza los dos relojes si hubo reinicio o si movieron la hora
        val newEndWall = nowWall + remaining
        if (!sameBoot || Math.abs(newEndWall - endWall) > 5_000L) {
            p.edit()
                .putLong(KEY_END_WALL, newEndWall)
                .putLong(KEY_END_ELAPSED, nowElapsed + remaining)
                .putInt(KEY_BOOT, bootCount(ctx))
                .apply()
        }
        return remaining
    }

    fun isActive(ctx: Context): Boolean = remainingMs(ctx) > 0L

    fun remainingMinutes(ctx: Context): Long = remainingMs(ctx) / 60_000L + 1

    fun isBlocked(ctx: Context, pkg: String): Boolean {
        if (!isActive(ctx)) return false
        val apps = prefs(ctx).getStringSet(KEY_APPS, emptySet()) ?: emptySet()
        return pkg in apps
    }
}
