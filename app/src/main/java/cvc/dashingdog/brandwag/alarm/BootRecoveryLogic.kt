package cvc.dashingdog.brandwag.alarm

import android.content.Context
import cvc.dashingdog.brandwag.data.repository.AlarmSoundingRepository
import cvc.dashingdog.brandwag.data.repository.BurnStateRepository
import cvc.dashingdog.brandwag.data.repository.SnoozeRepository
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Shared recovery logic, called from BOTH BootCompletedReceiver and
 * TimeChangedReceiver - a device correcting its clock shortly after boot
 * (NTP sync) needs to re-run the exact same check, not a separate copy of it.
 *
 * DESIGN RULE - persist/schedule durable state BEFORE clearing the evidence
 * that justified it. An AlarmManager.setAlarmClock() registration is OS-level
 * durable the instant it succeeds, independent of our own process; the
 * DataStore flags that got us here are not durable until written. So every
 * branch below schedules (and persists the new SnoozeState, where relevant)
 * FIRST, and only clears the stale flag AFTER.
 *
 * CLOCK-SANITY GUARD (added after a real false-disarm during Hisense battery-
 * pull testing, suspected RTC reset with no backup capacitor on a budget/Go
 * device): a raw armedDate that reads AFTER the current clock is ambiguous -
 * it could be a genuine future-dated bug, or it could mean the device's own
 * clock is currently wrong (very plausible right after boot, before any NTP
 * resync). Rather than guess, this case is left ALONE - no destructive
 * clearing happens - until TimeChangedReceiver confirms the clock has
 * actually been corrected and this logic runs again with a trustworthy `now`.
 */
object BootRecoveryLogic {

    private const val TAG = "BootRecoveryLogic"

    suspend fun run(context: Context) {
        val burnRepo = BurnStateRepository(context)
        val raw = burnRepo.getRawBurnState() // bypasses resolveBurnState's data-discarding fail-safe
        val today = LocalDate.now()

        android.util.Log.i(TAG, "run() - today=$today, raw.armed=${raw.armed}, raw.armedDate=${raw.armedDate}")

        if (raw.armed && raw.armedDate != null && today.isBefore(raw.armedDate)) {
            // Ambiguous: clock reads earlier than the stored arm date. Do NOT clear
            // SnoozeState/AlarmSoundingState here - if this is actually a bad post-boot
            // clock, TimeChangedReceiver will re-run this once the clock corrects, and
            // the still-intact pending state will be handled correctly then. Clearing now
            // would silently destroy real pending-alarm data based on an untrustworthy read.
            android.util.Log.w(TAG, "DEFERRED: clock ($today) reads before stored armedDate (${raw.armedDate}) - clock may be unreliable. Not touching pending state.")
            return
        }

        val burnState = burnRepo.getBurnState(today)
        if (!burnState.armed) {
            android.util.Log.i(TAG, "Resolved disarmed (genuine rollover, or armed=false) - clearing, no re-fire")
            AlarmScheduler.cancelSnooze(context)
            SnoozeRepository(context).clear()
            AlarmSoundingRepository(context).setIdle()
            return
        }

        val soundingState = AlarmSoundingRepository(context).getState()
        val snoozeState = SnoozeRepository(context).getSnoozeState()

        when {
            soundingState.isSounding -> {
                // Covers both "was genuinely sounding" AND the both-flags-true crash-window
                // case - per Chris's rule, don't trust any stored `until` in either case,
                // recalculate a fresh window from now.
                android.util.Log.i(TAG, "Was sounding (or ambiguous double-flag) - scheduling re-fire in ${AlarmForegroundService.SNOOZE_DURATION_MINUTES}min")
                val gustKmh = soundingState.maxGustKmh ?: snoozeState.maxGustKmh
                val until = Instant.now().plus(AlarmForegroundService.SNOOZE_DURATION_MINUTES, ChronoUnit.MINUTES)

                AlarmScheduler.scheduleSnooze(context, until, gustKmh ?: Double.NaN)
                DebugTestDelay.mark("bootRecovery(was-sounding): AlarmManager registered, about to persist SnoozeState + clear stale AlarmSoundingState")
                SnoozeRepository(context).schedule(until, gustKmh ?: Double.NaN)
                AlarmSoundingRepository(context).setIdle()
            }
            snoozeState.active -> {
                val now = Instant.now()
                val fireAt = if (snoozeState.until.isAfter(now)) snoozeState.until else now
                android.util.Log.i(TAG, "Snooze was pending until ${snoozeState.until} - re-registering at $fireAt")

                AlarmScheduler.scheduleSnooze(context, fireAt, snoozeState.maxGustKmh ?: Double.NaN)
                // SnoozeState already correctly reflects this - no rewrite needed unless the
                // target had already passed, in which case leaving the (now-past) `until`
                // as-is is harmless: AlarmForegroundService.startAlarm() clears SnoozeState
                // the moment this fires anyway.
            }
            else -> {
                android.util.Log.i(TAG, "Nothing pending")
            }
        }
    }
}