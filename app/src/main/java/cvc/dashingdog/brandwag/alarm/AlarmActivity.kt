package cvc.dashingdog.brandwag.alarm

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cvc.dashingdog.brandwag.R
import cvc.dashingdog.brandwag.databinding.ActivityAlarmBinding
import kotlinx.coroutines.launch

/**
 * Full-screen alarm UI, launched via the foreground service's full-screen
 * intent. Applies a per-window brightness override and FLAG_KEEP_SCREEN_ON
 * so the alarm stays visible/vibrating regardless of device brightness or
 * screen-timeout settings (see keepScreenOn() for the full 4a story).
 *
 * As of 4c: reflects AlarmStateHolder rather than only trusting its own
 * launch Intent extras. This is what lets the activity react correctly if
 * the service stops out from under it (10min timeout, Dismiss, future
 * Snooze) instead of sitting there showing a dead alarm screen.
 *
 * Dismiss is real as of 4c. Snooze remains a stop-and-close stub until 4b.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyBrightnessOverride()
        keepScreenOn()
        bindButtons()
        observeAlarmState()
    }

    /**
     * Single source of truth for what this screen shows, replacing the old
     * "read the launch Intent once" approach. Handles two cases the old
     * approach couldn't:
     *  - Activity launched (e.g. from a stale notification tap) after the
     *    service has already stopped - finishes immediately rather than
     *    showing a live-looking screen for an alarm that's already over.
     *  - Activity visible when the service stops out from under it - reacts
     *    and finishes, instead of relying on the service to somehow reach
     *    into the activity directly.
     */
    private fun observeAlarmState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmStateHolder.state.collect { state ->
                    when (state) {
                        is AlarmStateHolder.State.Sounding -> bindGustText(state.maxGustKmh)
                        AlarmStateHolder.State.Idle -> finish()
                    }
                }
            }
        }
    }

    /**
     * Prevents the screen from timing out while this activity is resumed.
     * Root cause of the vibration/visual cutting out after ~10-30s (confirmed
     * on both Huawei P20 Pro/EMUI and Hisense Android 11 Go, so NOT an OEM
     * throttling issue): the device's normal screen-timeout was firing during
     * an active alarm, and the resulting screen-off transition was what cut
     * vibration - sound survived because it's tied to the audio stream, not
     * the window. Genuine alarm-clock apps hold the screen open explicitly
     * rather than depending on the user's timeout setting; this does the same.
     *
     * Does NOT protect against a manual power-button press, which is a hard
     * OS-level override no app can prevent - if that turns out to also kill
     * vibration, it's an accepted limitation, not a bug (same class as the
     * Force Stop limitation already accepted for Phase 4).
     */
    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Overrides brightness for THIS WINDOW ONLY - reverts automatically when
     * the activity is destroyed. Does not touch the system-wide brightness
     * setting, so no WRITE_SETTINGS permission is needed.
     */
    private fun applyBrightnessOverride() {
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = params
    }

    private fun bindGustText(maxGustKmh: Double) {
        binding.alarmGustText.text = if (maxGustKmh.isNaN()) {
            getString(R.string.alarm_notification_text_no_reading)
        } else {
            getString(R.string.alarm_notification_text_with_gust, maxGustKmh.toInt())
        }
    }

    private fun bindButtons() {
        binding.dismissButton.setOnClickListener {
            // Real dismiss (4c): stop the service. Deliberately does NOT touch
            // LastCheckRepository/TriggerLogic.DEFAULT_COOLDOWN - the next poll's
            // decision is unaffected by whether this alarm was dismissed. finish()
            // isn't called directly here; AlarmStateHolder flipping to Idle (inside
            // stopAlarm()) is what triggers observeAlarmState() to finish this screen -
            // single path for "alarm ended," same one the 10min timeout uses.
            AlarmForegroundService.stop(this)
        }
        binding.snoozeButton.setOnClickListener {
            // Real snooze (4b): persists SnoozeState, schedules the AlarmManager
            // re-sound callback, and stops the current sound/vibration loop - all
            // handled inside AlarmForegroundService.snoozeAlarm(). Screen closes
            // via AlarmStateHolder flipping to Idle, same path as Dismiss/timeout.
            AlarmForegroundService.snooze(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Gust text now comes from AlarmStateHolder (see observeAlarmState), not the
        // Intent, so no re-bind needed here - kept override only to accept the new
        // Intent cleanly and avoid a stale getIntent() elsewhere.
    }
}