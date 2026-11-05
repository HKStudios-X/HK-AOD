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
    // 0 = not decided yet. Only decided while charging so idle readings cannot
    // poison the unit for a later charging session.
    private var currentDivisor = 0f

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

        // No charging sample yet: use the documented µA unit. Don't persist
        // this choice because idle/charge-counter values can be tiny.
        val divisor = if (currentDivisor != 0f) currentDivisor else 1000f
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
