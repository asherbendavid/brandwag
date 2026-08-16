package cvc.dashingdog.brandwag

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cvc.dashingdog.brandwag.alarm.AlarmCheckPipeline
import cvc.dashingdog.brandwag.alarm.AlarmForegroundService
import cvc.dashingdog.brandwag.alarm.AlarmScheduler

class MainActivity : AppCompatActivity() {

    // Placeholder gust value for manual testing only - real trigger chain (Phase 5)
    // will supply this from TriggerLogic.evaluate()'s actual Alarm.maxGustKmh.
    private val testMaxGustKmh = 65.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindTestTriggers()
    }

    private fun bindTestTriggers() {
        findViewById<android.widget.Button>(R.id.triggerImmediateButton).setOnClickListener {
            // Bypasses AlarmManager entirely - fastest loop for testing sound/vibration/
            // full-screen intent/brightness override while the app is in the foreground.
            AlarmForegroundService.start(this, testMaxGustKmh)
        }

        findViewById<android.widget.Button>(R.id.trigger1MinButton).setOnClickListener {
            // Tests the full delivery path while the app is backgrounded/dismissed:
            // AlarmManager -> AlarmTestReceiver -> AlarmForegroundService.
            AlarmScheduler.scheduleAlarm(
                context = this,
                triggerAtMillis = System.currentTimeMillis() + 60_000L,
                maxGustKmh = testMaxGustKmh,
                requestCode = AlarmScheduler.REQUEST_CODE_TEST_1MIN
            )
        }

        findViewById<android.widget.Button>(R.id.trigger5MinButton).setOnClickListener {
            // Long enough to reboot the device mid-countdown - proves whether AlarmManager's
            // own OS-level persistence holds. Does NOT test our BOOT_COMPLETED re-registration
            // logic (that's 4d, not yet built) - this only tests the OS's baseline guarantee.
            AlarmScheduler.scheduleAlarm(
                context = this,
                triggerAtMillis = System.currentTimeMillis() + 5 * 60_000L,
                maxGustKmh = testMaxGustKmh,
                requestCode = AlarmScheduler.REQUEST_CODE_TEST_5MIN
            )
        }

        findViewById<android.widget.Button>(R.id.trigger11MinButton).setOnClickListener {
            // Proxy for the "stale activity" edge case, which can't be triggered directly -
            // the full-screen alarm blocks access to these buttons while it's showing.
            // Instead: tap this, then immediately tap Trigger Immediately. The first alarm
            // auto-stops at ~10min, returning control; this one fires ~1min later, proving
            // the 10min timeout genuinely releases everything and a fresh alarm starts clean
            // with no leftover state from the first.
            AlarmScheduler.scheduleAlarm(
                context = this,
                triggerAtMillis = System.currentTimeMillis() + 11 * 60_000L,
                maxGustKmh = testMaxGustKmh,
                requestCode = AlarmScheduler.REQUEST_CODE_TEST_11MIN
            )
        }

        findViewById<android.widget.Button>(R.id.triggerDedupWideGapButton).setOnClickListener {
            // Wide gap (3s malformed vs ~200-600ms good): easy to pass, mainly proves
            // evaluation isn't gated at all - not a real test of the commit mutex itself.
            AlarmCheckPipeline.trigger(this, "malformed", simulateMalformed = true)
            AlarmCheckPipeline.trigger(this, "good-data", simulateGustKmh = testMaxGustKmh)
        }

        findViewById<android.widget.Button>(R.id.triggerDedupTightRaceButton).setOnClickListener {
            // Tight race, submitted in the order malformed-then-good, both ~5-10ms delay -
            // forces genuine near-simultaneous arrival at the commit mutex. This is the
            // test that actually exercises Mutex correctness under contention.
            AlarmCheckPipeline.trigger(this, "malformed", simulateMalformed = true, evaluateDelayMs = 7L)
            AlarmCheckPipeline.trigger(this, "good-data", simulateGustKmh = testMaxGustKmh, evaluateDelayMs = 9L)
        }

        findViewById<android.widget.Button>(R.id.triggerDedupTightRaceReversedButton).setOnClickListener {
            // Same tight race, submission order reversed (good-data submitted first this
            // time). Proves the result doesn't depend on which trigger was SUBMITTED first,
            // only on genuine data validity - submission order must never matter.
            AlarmCheckPipeline.trigger(this, "good-data", simulateGustKmh = testMaxGustKmh, evaluateDelayMs = 8L)
            AlarmCheckPipeline.trigger(this, "malformed", simulateMalformed = true, evaluateDelayMs = 6L)
        }
    }
}