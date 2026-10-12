package com.mudassir.vidstash

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * When downloads may run: only while charging, only at night (12 AM – 7 AM), and a daily
 * limit for mobile data (after which downloads wait for Wi-Fi).
 */
object DownloadRules {

    private const val NIGHT_START = 0 // 12 AM
    private const val NIGHT_END = 7   // 7 AM

    fun nightOnly(ctx: Context) = Settings(ctx).downloadTime == "night"

    fun inNightWindow(cal: Calendar = Calendar.getInstance()): Boolean =
        cal.get(Calendar.HOUR_OF_DAY) in NIGHT_START until NIGHT_END

    /** Milliseconds until downloads are allowed by the clock (0 = now). */
    fun delayUntilAllowed(ctx: Context): Long {
        if (!nightOnly(ctx)) return 0
        val now = Calendar.getInstance()
        if (inNightWindow(now)) return 0
        val start = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, NIGHT_START); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return start.timeInMillis - now.timeInMillis
    }

    // ---------- mobile data ----------

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Bytes downloaded on mobile data today. */
    fun mobileUsedToday(ctx: Context): Long {
        val s = Settings(ctx)
        return if (s.mobileDataDay == today()) s.mobileDataBytes else 0
    }

    fun addMobileUsage(ctx: Context, bytes: Long) {
        if (bytes <= 0 || !onMobileData(ctx)) return
        val s = Settings(ctx)
        val used = mobileUsedToday(ctx)
        s.mobileDataDay = today()
        s.mobileDataBytes = used + bytes
    }

    fun overMobileLimit(ctx: Context): Boolean {
        val limit = Settings(ctx).mobileDataLimitBytes
        return limit > 0 && mobileUsedToday(ctx) >= limit
    }

    fun onMobileData(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun charging(ctx: Context): Boolean {
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return true
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Whether the next download may start now. */
    fun canStartNow(ctx: Context): Boolean =
        delayUntilAllowed(ctx) == 0L &&
            !(onMobileData(ctx) && overMobileLimit(ctx)) &&
            !(Settings(ctx).onlyWhileCharging && !charging(ctx))

    /** Why queued downloads are waiting, for the Downloads screen (null = not held back). */
    fun waitingReason(ctx: Context): String? = when {
        nightOnly(ctx) && !inNightWindow() -> "Waiting for night (12 AM – 7 AM)"
        Settings(ctx).onlyWhileCharging && !charging(ctx) -> "Waiting for the charger"
        onMobileData(ctx) && overMobileLimit(ctx) -> "Today's mobile data limit is used up · waiting for Wi-Fi"
        else -> null
    }
}
