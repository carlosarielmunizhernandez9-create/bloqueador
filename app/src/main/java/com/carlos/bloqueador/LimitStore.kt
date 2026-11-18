package com.carlos.bloqueador

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Límites diarios por app. El "día" son ventanas de 24 h del reloj interno. */
object LimitStore {
    private const val PREFS = "limites"
    private const val KEY_LIMITS = "limits"
    private const val KEY_USAGE = "usage"
    private const val KEY_START_ELAPSED = "start_elapsed"
    private const val KEY_START_WALL = "start_wall"
    private const val KEY_BOOT = "boot"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val limits = HashMap<String, Int>()
    private val usage = HashMap<String, Long>()
    private var loaded = false
    private var startElapsed = 0L
    private var startWall = 0L
    private var boot = -1
    private var bootNow: Int? = null
    private var lastSave = 0L
    private var lastSync = 0L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun currentBoot(ctx: Context): Int {
        bootNow?.let { return it }
        val v = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        bootNow = v
        return v
    }

    private fun ensure(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (!loaded) {
            load(ctx)
            lastSync = now
        } else if (now - lastSync > 5_000L) {
            lastSync = now
            syncWindow(ctx)
        }
    }

    private fun load(ctx: Context) {
        val p = prefs(ctx)
        limits.clear()
        usage.clear()
        for (s in p.getStringSet(KEY_LIMITS, emptySet()) ?: emptySet()) {
            val parts = s.split("|")
            if (parts.size == 2) parts[1].toIntOrNull()?.let { limits[parts[0]] = it }
        }
        for (s in p.getStringSet(KEY_USAGE, emptySet()) ?: emptySet()) {
            val parts = s.split("|")
            if (parts.size == 2) parts[1].toLongOrNull()?.let { usage[parts[0]] = it }
        }
        startElapsed = p.getLong(KEY_START_ELAPSED, -1L)
        startWall = p.getLong(KEY_START_WALL, 0L)
        boot = p.getInt(KEY_BOOT, -1)
        loaded = true
        if (startWall == 0L) newWindow(ctx) else syncWindow(ctx)
    }

    private fun newWindow(ctx: Context) {
        usage.clear()
        startElapsed = SystemClock.elapsedRealtime()
        startWall = System.currentTimeMillis()
        boot = currentBoot(ctx)
        save(ctx)
    }

    /** Revisa si pasaron 24 h sin fiarse de la hora del teléfono. */
    private fun syncWindow(ctx: Context) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val sameBoot = boot == currentBoot(ctx)
        val age = if (sameBoot) nowElapsed - startElapsed else nowWall - startWall
        if (age >= DAY_MS) {
            newWindow(ctx)
            return
        }
        val safeAge = if (age < 0L) 0L else age
        if (!sameBoot || Math.abs((nowWall - safeAge) - startWall) > 5_000L) {
            startElapsed = nowElapsed - safeAge
            startWall = nowWall - safeAge
            boot = currentBoot(ctx)
            save(ctx)
        }
    }

    private fun save(ctx: Context) {
        lastSave = SystemClock.elapsedRealtime()
        prefs(ctx).edit()
            .putStringSet(KEY_LIMITS, limits.map { "${it.key}|${it.value}" }.toSet())
            .putStringSet(KEY_USAGE, usage.map { "${it.key}|${it.value}" }.toSet())
            .putLong(KEY_START_ELAPSED, startElapsed)
            .putLong(KEY_START_WALL, startWall)
            .putInt(KEY_BOOT, boot)
            .apply()
    }

    @Synchronized
    fun limitMinutes(ctx: Context, pkg: String): Int? {
        ensure(ctx)
        return limits[pkg]
    }

    @Synchronized
    fun usedMs(ctx: Context, pkg: String): Long {
        ensure(ctx)
        return usage[pkg] ?: 0L
    }

    @Synchronized
    fun setLimit(ctx: Context, pkg: String, minutes: Int?) {
        ensure(ctx)
        if (minutes == null || minutes <= 0) limits.remove(pkg) else limits[pkg] = minutes
        save(ctx)
    }

    @Synchronized
    fun addUsage(ctx: Context, pkg: String, ms: Long) {
        ensure(ctx)
        usage[pkg] = (usage[pkg] ?: 0L) + ms
        if (SystemClock.elapsedRealtime() - lastSave > 10_000L) save(ctx)
    }

    @Synchronized
    fun isOverLimit(ctx: Context, pkg: String): Boolean {
        ensure(ctx)
        val lim = limits[pkg] ?: return false
        return (usage[pkg] ?: 0L) >= lim * 60_000L
    }
}
