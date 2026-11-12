package com.hktamizhan.hkaod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlin.math.abs

/**
 * One battery / charging snapshot for the AOD charging intel.
 *
 * [currentMa] is always a magnitude (>= 0). [signedCurrentMa] keeps the
 * direction: positive means current is going into the battery, negative means
 * the battery is supplying the phone (or a weak charger is being "leached").
 */
data class ChargingReading(
    val status: Int,
    val plugged: Int,
    val isCharging: Boolean,
    val isPlugged: Boolean,
    val levelPercent: Int,
    val currentMa: Float,
    val signedCurrentMa: Float,
    val voltageV: Float,
    val watts: Float,
    val signedWatts: Float,
    val chargeCounterUah: Long,
    val source: String,
    val hasCurrent: Boolean,
    val hasVoltage: Boolean
) {
    /** A charger is attached but the battery is supplying current. */
    val isDrainingWhilePlugged: Boolean
        get() = isPlugged && !isCharging && signedCurrentMa < -150f
}

/**
 * Learns the real average battery drain (% per hour) while unplugged, so the
 * AOD can show "time remaining" on devices without the Android 12+
 * PowerManager.batteryDischargePrediction. Fed by AODService on every
 * ACTION_BATTERY_CHANGED; state lives in SharedPreferences so it survives
 * process restarts.
 *
 *  * session rate  — drop since the phone was unplugged (needs ≥ 2 % and
 *                    ≥ 15 min, so it reflects real mixed use, not one spike)
 *  * history rate  — smoothed rate from previous discharge sessions, used
 *                    until the current session has enough data
 */
object BatteryDrainTracker {
    private const val PREFS = "HKAOD_PREFS"
    private const val K_ANCHOR_AT    = "drain_anchor_at"
    private const val K_ANCHOR_LEVEL = "drain_anchor_level"
    private const val K_SESSION_RATE = "drain_session_rate"
    private const val K_HISTORY_RATE = "drain_history_rate"

    private const val MIN_DROP_PCT = 2
    private const val MIN_ELAPSED_MS = 15 * 60 * 1000L

    fun onBatteryChanged(context: Context, levelPercent: Int, plugged: Boolean) {
        if (levelPercent !in 0..100) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val anchorAt = p.getLong(K_ANCHOR_AT, 0L)

        if (plugged) {
            if (anchorAt != 0L) {
                // Session over — fold its rate into the long-term history.
                val session = p.getFloat(K_SESSION_RATE, 0f)
                val history = p.getFloat(K_HISTORY_RATE, 0f)
                val edit = p.edit().putLong(K_ANCHOR_AT, 0L).putFloat(K_SESSION_RATE, 0f)
                if (session > 0f) {
                    edit.putFloat(K_HISTORY_RATE, if (history > 0f) history * 0.6f + session * 0.4f else session)
                }
                edit.apply()
            }
            return
        }

        val anchorLevel = p.getInt(K_ANCHOR_LEVEL, -1)
        // New session, or the level went *up* without a plug event (reboot,
        // calibration jump) — re-anchor.
        if (anchorAt == 0L || anchorLevel < 0 || levelPercent > anchorLevel) {
            p.edit().putLong(K_ANCHOR_AT, now).putInt(K_ANCHOR_LEVEL, levelPercent)
                .putFloat(K_SESSION_RATE, 0f).apply()
            return
        }

        val dropped = anchorLevel - levelPercent
        val elapsed = now - anchorAt
        if (dropped >= MIN_DROP_PCT && elapsed >= MIN_ELAPSED_MS) {
            val ratePerHour = dropped / (elapsed / 3_600_000f)
            p.edit().putFloat(K_SESSION_RATE, ratePerHour).apply()
        }
    }

    /** Estimated hours left at [levelPercent], or null if nothing learned yet. */
    fun hoursLeft(context: Context, levelPercent: Int): Float? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val session = p.getFloat(K_SESSION_RATE, 0f)
        val history = p.getFloat(K_HISTORY_RATE, 0f)
        val rate = when {
            session > 0f -> session
            history > 0f -> history
            else -> return null
        }.coerceAtLeast(0.3f) // never extrapolate beyond ~2 weeks
        return levelPercent / rate
    }

    /** "1d 4h left", "5h 20m left", "45m left" — empty when not meaningful. */
    fun format(hours: Float?, approximate: Boolean = false): String {
        if (hours == null || hours.isNaN() || hours <= 0f || hours > 24f * 14f) return ""
        val totalMin = (hours * 60f).toInt()
        val d = totalMin / (60 * 24)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        val body = when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> return ""
        }
        return (if (approximate) "~" else "") + "$body left"
    }
}

/**
 * Robust reader for the values behind "Charging Speed Indication".
 *
 * The Android battery API is notoriously inconsistent across OEMs:
 *
 *  * [BatteryManager.getIntProperty] returns `Integer.MIN_VALUE` when a
 *    property is unsupported, and [BatteryManager.getLongProperty] returns
 *    `Long.MIN_VALUE`. The old AOD code divided those sentinels by 1000 and
 *    then rejected the resulting absurd rate, so watts stayed blank on those
 *    devices.
 *  * Some devices expose current only through CURRENT_AVERAGE, others only
 *    through CHARGE_COUNTER deltas.
 *  * A few OEMs report current in mA instead of the documented µA, and some
 *    invert the sign while charging.
 *  * EXTRA_VOLTAGE can be omitted.
 *
 * This reader mirrors the working WattMeter approach (`getLongProperty`), then
 * validates every value and fills the gaps with fallbacks. It is a plain class
 * so a Compose screen can keep one instance across polling ticks (the charge
 * counter anchor needs to survive between reads).
 */
class ChargingIntelReader {

    // Charge-counter anchor used for the delta fallback / unit detection.
    private var counterAnchorUah = -1L
    private var counterAnchorAt = 0L
    private var counterRateMa = Float.NaN
    private var counterSignedRateMa = Float.NaN
    private var counterRateAt = 0L

    // 1000 = the OEM reports µA (the documented unit), 1 = the OEM reports mA.
    // 0 = not decided yet. Shared across reader instances (a new reader is
    // created every time the AOD appears) so the decision survives.
    private var currentDivisor: Float
        get() = sharedDivisor
        set(v) { sharedDivisor = v }

    companion object {
        @Volatile private var sharedDivisor = 0f
    }

    private var lastLogSignature = ""

    fun read(context: Context): ChargingReading {
        val batteryManager = try {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        } catch (_: Exception) {
            null
        }

        // A null receiver reads the current sticky battery broadcast without
        // registering a permanent receiver.
        val sticky = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val rawVoltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val percent = if (scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            0
        }

        val isPlugged = plugged > 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val now = System.currentTimeMillis()

        // ── Charge counter ────────────────────────────────────────────────
        // getLongProperty is the correct API (WattMeter uses it); fall back to
        // the int getter and validate sentinels/absurd values.
        val chargeCounterUah = batteryManager
            ?.let { readBatteryProperty(it, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }
            ?.takeIf { it in 1L..200_000_000L }
            ?: -1L
        updateCounterRate(chargeCounterUah, now)

        // ── Current ───────────────────────────────────────────────────────
        val rawNow = batteryManager
            ?.let { readBatteryProperty(it, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
        val rawAverage = batteryManager
            ?.let { readBatteryProperty(it, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) }

        // CURRENT_NOW is preferred. When it is missing/zero use CURRENT_AVERAGE.
        val rawCurrent = when {
            rawNow != null && rawNow != 0L -> rawNow
            rawAverage != null && rawAverage != 0L -> rawAverage
            rawNow != null -> rawNow
            else -> rawAverage
        }

        var hasCurrent = false
        var currentMa = 0f
        var signedCurrentMa = 0f

        if (rawCurrent != null) {
            val scaled = normalizeCurrent(rawCurrent, isCharging, percent)
            // A genuine zero is useful near full ("0.0 W"); other zero reads
            // are treated as unavailable so the counter fallback can run.
            if (scaled != null && (scaled != 0f || status == BatteryManager.BATTERY_STATUS_FULL)) {
                hasCurrent = true
                signedCurrentMa = scaled
                currentMa = abs(scaled)
            }
        }

        // Last resort: charge-counter delta. This is slower (needs a few
        // seconds of samples) but works on phones that expose no current
        // property at all. Only use a fresh estimate.
        val freshCounterRate = counterRateAt > 0L && (now - counterRateAt) <= 25_000L
        if (!hasCurrent && freshCounterRate && !counterSignedRateMa.isNaN()) {
            val rate = abs(counterSignedRateMa)
            if (rate >= 5f) {
                signedCurrentMa = counterSignedRateMa
                currentMa = rate
                hasCurrent = true
            }
        }

        // Normalise direction using the battery status, because several OEMs
        // invert CURRENT_NOW's sign. Public API: positive = into the battery.
        if (hasCurrent) {
            signedCurrentMa = normalizeSign(signedCurrentMa, status)
            currentMa = abs(signedCurrentMa)
        }

        // ── Voltage ───────────────────────────────────────────────────────
        // EXTRA_VOLTAGE is in mV. Accept only a plausible single-cell range;
        // otherwise estimate from the state of charge so watts still display.
        var voltageV = 0f
        var hasVoltage = false
        if (rawVoltageMv in 2_500..5_500) {
            voltageV = rawVoltageMv / 1000f
            hasVoltage = true
        } else if (isPlugged || isCharging) {
            // Battery-side estimate from state of charge (3.3 V empty → 4.2 V
            // full). Better than showing nothing when EXTRA_VOLTAGE is absent.
            voltageV = 3.3f + (percent.coerceIn(0, 100) / 100f) * 0.9f
            hasVoltage = true
        }

        val watts = if (hasCurrent && hasVoltage) currentMa * voltageV / 1000f else 0f
        val signedWatts = if (hasCurrent && hasVoltage) signedCurrentMa * voltageV / 1000f else 0f

        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
            else -> "Unplugged"
        }

        // Log only when the diagnostic signature changes (not every second).
        val signature = "status=$status plug=$plugged div=$currentDivisor " +
                "raw=${rawCurrent ?: "null"} mA=${currentMa.toInt()} " +
                "v=$voltageV w=${"%.1f".format(watts)}"
        if (signature != lastLogSignature) {
            lastLogSignature = signature
            Log.d(
                "HKAOD",
                "Charging Intel: status=$status plugged=$plugged level=$percent% " +
                        "rawCurrent=${rawCurrent ?: "unavailable"} divisor=$currentDivisor " +
                        "current=${"%.0f".format(currentMa)}mA signed=${"%.0f".format(signedCurrentMa)}mA " +
                        "voltage=${"%.2f".format(voltageV)}V watts=${"%.1f".format(watts)}W " +
                        "counter=${chargeCounterUah}µAh source=$source"
            )
        }

        return ChargingReading(
            status = status,
            plugged = plugged,
            isCharging = isCharging,
            isPlugged = isPlugged,
            levelPercent = percent,
            currentMa = currentMa,
            signedCurrentMa = signedCurrentMa,
            voltageV = voltageV,
            watts = watts,
            signedWatts = signedWatts,
            chargeCounterUah = chargeCounterUah,
            source = source,
            hasCurrent = hasCurrent,
            hasVoltage = hasVoltage
        )
    }

    /**
     * Reads a BatteryManager property without letting an OEM exception or an
     * unsupported-property sentinel leak into the UI.
     */
    private fun readBatteryProperty(batteryManager: BatteryManager, property: Int): Long? {
        // getLongProperty is what the working WattMeter app uses and avoids
        // int overflow on high-current devices.
        try {
            val value = batteryManager.getLongProperty(property)
            if (value != Long.MIN_VALUE &&
                value != Int.MIN_VALUE.toLong() &&
                abs(value) < 100_000_000L
            ) {
                return value
            }
        } catch (_: Exception) {
            // Fall through to the int getter below.
        }

        try {
            val value = batteryManager.getIntProperty(property)
            if (value != Int.MIN_VALUE && abs(value) < 100_000_000) return value.toLong()
        } catch (_: Exception) {
            // Both getters failed — report unavailable.
        }
        return null
    }

    /**
     * Converts a raw current reading to mA, deciding once whether this OEM
     * reports µA (documented) or mA (some Samsung/MediaTek kernels).
     */
    private fun normalizeCurrent(raw: Long, isCharging: Boolean, percent: Int): Float? {
        val rawAbs = abs(raw).toFloat()
        if (rawAbs == 0f) return 0f
        if (rawAbs > 100_000_000f) return null

        // Pick a unit only while charging. Once picked, keep it so a device
        // cannot flip between µA and mA from one sample to the next.
        if (currentDivisor == 0f && isCharging) {
            val counterEstimate = counterRateMa
            currentDivisor = if (!counterEstimate.isNaN() && counterEstimate in 30f..25_000f) {
                val micro = rawAbs / 1000f
                val milli = rawAbs
                if (abs(micro - counterEstimate) <= abs(milli - counterEstimate)) 1000f else 1f
            } else {
                guessCurrentDivisor(rawAbs, percent)
            }
        }

        // Not charging and no unit decided yet. A charge-counter rate, when
        // available, settles it for good. Otherwise guess without persisting:
        // the AOD only reads this with the screen on, when real drain is
        // ≥ ~50 mA — i.e. ≥ 50,000 in µA. A raw value below 20,000 is
        // therefore almost certainly mA (Samsung reports e.g. "271").
        if (currentDivisor == 0f && !isCharging) {
            val counterEstimate = counterRateMa
            if (!counterEstimate.isNaN() && counterEstimate in 30f..25_000f) {
                val micro = rawAbs / 1000f
                currentDivisor = if (abs(micro - counterEstimate) <= abs(rawAbs - counterEstimate)) 1000f else 1f
            }
        }
        val divisor = when {
            currentDivisor != 0f -> currentDivisor
            !isCharging && rawAbs in 5f..20_000f -> 1f
            else -> 1000f
        }
        var ma = rawAbs / divisor

        // Self-heal a bad unit guess: a mA-mode reading above 20 A means the
        // OEM was actually reporting µA, and a µA-mode reading below 1 mA while
        // charging away from full is more likely an OEM that reports mA.
        if (isCharging) {
            if (currentDivisor == 1f && rawAbs > 20_000f) {
                currentDivisor = 1000f
                ma = rawAbs / 1000f
            } else if (currentDivisor == 1000f && rawAbs in 5f..20_000f && percent < 95) {
                currentDivisor = 1f
                ma = rawAbs
            }
        }

        if (ma !in 0.1f..25_000f) return null
        return if (raw < 0) -ma else ma
    }

    private fun guessCurrentDivisor(rawAbs: Float, percent: Int): Float {
        return when {
            // >= 20,000 µA = >= 20 mA. This is the normal charging range and
            // is unambiguous: the same number in mA would be >= 20 A.
            rawAbs >= 20_000f -> 1000f
            // Near full the true charge current can be a few mA; the documented
            // µA unit is far more likely than tens of amps.
            percent >= 95 -> 1000f
            // 5..19,999 is a plausible mA reading from an OEM that reports mA.
            rawAbs >= 5f -> 1f
            // Extremely small raw values stay in the documented µA unit.
            else -> 1000f
        }
    }

    private fun normalizeSign(value: Float, status: Int): Float {
        // Public contract: CURRENT_NOW is positive when net current enters the
        // battery. Some OEMs invert it; use the status to correct the sign.
        return when {
            status == BatteryManager.BATTERY_STATUS_CHARGING && value < 0f -> -value
            status == BatteryManager.BATTERY_STATUS_DISCHARGING && value > 0f -> -value
            else -> value
        }
    }

    /**
     * Maintains a charge-counter rate over a window long enough to beat the
     * counter's 1 mAh quantisation. 1 µAh/s == 3.6 mA.
     */
    private fun updateCounterRate(counterUah: Long, now: Long) {
        if (counterUah <= 0L) {
            counterAnchorUah = -1L
            counterAnchorAt = 0L
            return
        }

        if (counterAnchorUah < 0L || counterAnchorAt == 0L) {
            counterAnchorUah = counterUah
            counterAnchorAt = now
            return
        }

        val dt = now - counterAnchorAt
        if (dt < 8_000L) return

        val deltaUah = counterUah - counterAnchorUah
        val rate = abs(deltaUah).toFloat() * 3.6f / (dt / 1000f)
        if (rate in 0f..25_000f) {
            counterRateMa = rate
            counterSignedRateMa = if (deltaUah < 0L) -rate else rate
            counterRateAt = now
        }

        // Re-anchor periodically; also re-anchor immediately after a counter
        // reset (battery swap/reboot) or an impossible delta.
        if (dt >= 20_000L || rate > 25_000f) {
            counterAnchorUah = counterUah
            counterAnchorAt = now
        }
    }
}
