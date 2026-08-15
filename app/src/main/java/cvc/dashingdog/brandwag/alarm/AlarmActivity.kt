package cvc.dashingdog.brandwag.alarm

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import cvc.dashingdog.brandwag.R
import cvc.dashingdog.brandwag.databinding.ActivityAlarmBinding

/**
 * Full-screen alarm UI, launched via the foreground service's full-screen
 * intent. Applies a per-window brightness override so the alarm is visible
 * even if the device's system brightness has been turned down (e.g. workers
 * dimming for battery life during normal operation).
 *
 * 4a scope only: Snooze/Dismiss both just stop the sound/vibration and
 * close this screen. Real snooze re-scheduling (4b) and dedup-aware dismiss
 * (4c) land in later chunks - see PHASE4 handoff notes.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyBrightnessOverride()
        keepScreenOn()
        bindGustText()
        bindButtons()
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

    private fun bindGustText() {
        val maxGustKmh = intent.getDoubleExtra(
            AlarmForegroundService.EXTRA_MAX_GUST_KMH,
            Double.NaN
        )
        binding.alarmGustText.text = if (maxGustKmh.isNaN()) {
            getString(R.string.alarm_notification_text_no_reading)
        } else {
            getString(R.string.alarm_notification_text_with_gust, maxGustKmh.toInt())
        }
    }

    private fun bindButtons() {
        // Both wired to the same stop-and-close action for 4a, per Chris's instruction -
        // real Snooze (4b) and Dismiss (4c) behavior diverge in later chunks.
        binding.snoozeButton.setOnClickListener { stopAlarmAndFinish() }
        binding.dismissButton.setOnClickListener { stopAlarmAndFinish() }
    }

    private fun stopAlarmAndFinish() {
        AlarmForegroundService.stop(this)
        finish()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Guard against a second full-screen intent launching a duplicate instance
        // while one is already showing (FLAG_ACTIVITY_CLEAR_TOP should prevent this
        // in practice, but an explicit re-bind here is cheap insurance).
        setIntent(intent)
        bindGustText()
    }
}