package cvc.dashingdog.brandwag

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    }
}