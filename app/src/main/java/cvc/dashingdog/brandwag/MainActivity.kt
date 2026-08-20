package cvc.dashingdog.brandwag

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cvc.dashingdog.brandwag.alarm.AlarmCheckPipeline
import cvc.dashingdog.brandwag.alarm.AlarmForegroundService
import cvc.dashingdog.brandwag.alarm.AlarmScheduler
import cvc.dashingdog.brandwag.data.repository.BurnStateRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Placeholder gust value for manual testing only - real trigger chain (Phase 5)
    // will supply this from TriggerLogic.evaluate()'s actual Alarm.maxGustKmh.
    private val testMaxGustKmh = 65.0

    // Tracks which arm-gate dialog the debug sequence sent the user off to
    // Settings for, so onResume() knows which recheck function to call on
    // return. Real arm UI (Layer B) will need the same tracking - this is
    // the debug-button proving ground for that pattern, not a throwaway.
    private lateinit var armGateController: cvc.dashingdog.brandwag.arm.ArmGateController
    private lateinit var armSwitch: com.google.android.material.materialswitch.MaterialSwitch

    private enum class PendingGate { NONE, BATTERY_EXEMPTION, DND_ACCESS }
    private var pendingGate = PendingGate.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        armGateController = cvc.dashingdog.brandwag.arm.ArmGateController(this)
        armSwitch = findViewById(R.id.armSwitch)
        bindArmSwitch()
        bindTestTriggers()
    }

    private fun bindArmSwitch() {
        // Reflect real persisted state on load - Chris's call, matters once this isn't
        // the only screen (e.g. if the app is reopened mid-armed-day).
        lifecycleScope.launch {
            val burnState = cvc.dashingdog.brandwag.data.repository.BurnStateRepository(this@MainActivity).getBurnState()
            armSwitch.isChecked = burnState.armed
        }

        armSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                attemptArm()
            } else {
                attemptDisarm()
            }
        }
    }

    private fun attemptArm() {
        when (val result = armGateController.checkGatesOnArmAttempt()) {
            is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.Armed -> commitArm()
            is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.NeedsBatteryExemptionDialog ->
                showBatteryExemptionDialog()
            is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.NeedsDndAccessDialog ->
                showDndAccessDialog()
            else -> {
                android.util.Log.w("MainActivity", "Unexpected gate result on arm attempt: $result")
                snapSwitchOff()
            }
        }
    }

    private fun commitArm() {
        lifecycleScope.launch {
            try {
                armGateController.commitArm()
                android.util.Log.i("MainActivity", "Armed via real arm switch")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to persist armed state: ${e.message}")
                snapSwitchOff()
                showArmFailedDialog()
            }
        }
    }

    private fun attemptDisarm() {
        lifecycleScope.launch {
            // Step 1 (CRITICAL): persist armed=false first. This is the write that
            // actually governs whether TriggerLogic/future polls treat the farm as
            // armed - if this fails, the switch must NOT show disarmed, because the
            // underlying state genuinely is still armed. This is the must-never case:
            // showing disarmed while still armed risks a real wind-pickup alarm being
            // silently missed by the user believing they've already disarmed.
            val armedStateCleared = try {
                cvc.dashingdog.brandwag.data.repository.BurnStateRepository(this@MainActivity).setArmed(false)
                true
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "CRITICAL: setArmed(false) failed - still armed: ${e.message}")
                false
            }

            if (!armedStateCleared) {
                snapSwitchOn() // reflect reality - still armed - do NOT show disarmed
                showDisarmCriticalFailureDialog()
                return@launch
            }

            // Step 2 (lower stakes): armed=false is now confirmed persisted. Cancelling
            // the pending snooze/scheduled alarm is still important, but a failure here
            // means "a stale alarm might still sound once" not "the farm is silently
            // still armed" - worth a distinct, less alarming message.
            try {
                cvc.dashingdog.brandwag.alarm.AlarmDisarmHandler.onDisarmed(this@MainActivity)
                android.util.Log.i("MainActivity", "Disarmed via real arm switch")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Disarmed, but cancelling pending alarm/snooze failed: ${e.message}")
                showDisarmPartialFailureDialog()
            }
        }
    }

    private fun snapSwitchOn() {
        armSwitch.setOnCheckedChangeListener(null)
        armSwitch.isChecked = true
        bindArmSwitch()
    }

    private fun snapSwitchOff() {
        armSwitch.setOnCheckedChangeListener(null)
        armSwitch.isChecked = false
        bindArmSwitch()
    }

    private fun showBatteryExemptionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_dialog_title))
            .setMessage(getString(R.string.battery_dialog_body))
            .setPositiveButton(getString(R.string.battery_dialog_grant)) { _, _ ->
                pendingGate = PendingGate.BATTERY_EXEMPTION
                startActivity(armGateController.buildBatteryExemptionIntent())
            }
            .setNegativeButton(getString(R.string.dialog_not_now)) { _, _ -> snapSwitchOff() }
            .setOnCancelListener { snapSwitchOff() }
            .setCancelable(true)
            .show()
    }

    private fun showDndAccessDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dnd_dialog_title))
            .setMessage(getString(R.string.dnd_dialog_body))
            .setPositiveButton(getString(R.string.dnd_dialog_open_settings)) { _, _ ->
                pendingGate = PendingGate.DND_ACCESS
                startActivity(armGateController.buildDndAccessSettingsIntent())
            }
            .setNegativeButton(getString(R.string.dialog_not_now)) { _, _ -> snapSwitchOff() }
            .setOnCancelListener { snapSwitchOff() }
            .setCancelable(true)
            .show()
    }

    private fun showBlockedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.blocked_dialog_title))
            .setMessage(getString(R.string.blocked_dialog_body))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (pendingGate == PendingGate.NONE) return

        val gateThatWasPending = pendingGate
        pendingGate = PendingGate.NONE

        val result = when (gateThatWasPending) {
            PendingGate.BATTERY_EXEMPTION -> armGateController.recheckAfterBatteryExemptionPrompt()
            PendingGate.DND_ACCESS -> armGateController.recheckAfterDndAccessPrompt()
            PendingGate.NONE -> return // unreachable, guarded above
        }

        when (result) {
            is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.Armed -> commitArm()
            is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.NeedsDndAccessDialog ->
                showDndAccessDialog() // auto-chain, confirmed
            is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.BlockedBatteryExemptionDenied -> {
                snapSwitchOff()
                showBlockedDialog()
            }
            is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.BlockedDndAccessDenied -> {
                snapSwitchOff()
                showBlockedDialog()
            }
            else -> android.util.Log.w("MainActivity", "Unexpected gate result on resume recheck: $result")
        }
    }

    private fun bindTestTriggers() {
        findViewById<android.widget.Button>(R.id.debugCheckGateStatusButton).setOnClickListener {
            // DEBUG ONLY - read-only, no dialogs, no side effects. Quick sanity check of
            // ArmGateChecks against current device state before exercising the full
            // ArmGateController sequence below.
            val batteryOk = cvc.dashingdog.brandwag.arm.ArmGateChecks.isBatteryExemptionGranted(this)
            val dndOk = cvc.dashingdog.brandwag.arm.ArmGateChecks.isDndAccessGranted(this)
            android.util.Log.i("MainActivity", "Debug: gate status - battery=$batteryOk dnd=$dndOk")
        }

        findViewById<android.widget.Button>(R.id.debugRunArmGateSequenceButton).setOnClickListener {
            val controller = cvc.dashingdog.brandwag.arm.ArmGateController(this)
            when (val result = controller.checkGatesOnArmAttempt()) {
                is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.Armed ->
                    android.util.Log.i("MainActivity", "Debug: both gates already pass")
                is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.NeedsBatteryExemptionDialog -> {
                    android.util.Log.i("MainActivity", "Debug: launching battery exemption intent")
                    pendingGate = PendingGate.BATTERY_EXEMPTION
                    startActivity(controller.buildBatteryExemptionIntent())
                }
                is cvc.dashingdog.brandwag.arm.ArmGateController.GateResult.NeedsDndAccessDialog -> {
                    android.util.Log.i("MainActivity", "Debug: launching DND access settings intent")
                    pendingGate = PendingGate.DND_ACCESS
                    startActivity(controller.buildDndAccessSettingsIntent())
                }
                else -> android.util.Log.i("MainActivity", "Debug: unexpected result $result")
            }
        }

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

        findViewById<android.widget.Button>(R.id.debugArmButton).setOnClickListener {
            // DEBUG ONLY - no real arm-toggle UI exists yet (that's a later phase). This
            // ONLY flips the persisted flag via the existing Phase 2 repository/domain logic -
            // deliberately NOT wired to anything else. In particular this must NOT survive
            // as a real UI action once Settings/burn-toggle exists; remove this button then.
            lifecycleScope.launch {
                BurnStateRepository(this@MainActivity).setArmed(true)
                android.util.Log.i("MainActivity", "Debug: burn state armed")
            }
        }

        findViewById<android.widget.Button>(R.id.debugDisarmButton).setOnClickListener {
            // DEBUG ONLY - same as above. Deliberately calls ONLY setArmed(false), nothing
            // else (no AlarmScheduler.cancelSnooze(), no AlarmDisarmHandler). This is what
            // lets 4d testing simulate a REALISTIC disarm - e.g. armedDate rolling over
            // stale overnight while the phone was off - where genuinely nothing else runs
            // to clean up pending alarm/snooze state except whatever reads burn state next
            // (BootCompletedReceiver). Tapping AlarmDisarmHandler.onDisarmed() here would
            // test a different, easier scenario that doesn't match the real gap.
            lifecycleScope.launch {
                BurnStateRepository(this@MainActivity).setArmed(false)
                android.util.Log.i("MainActivity", "Debug: burn state disarmed (no cleanup triggered)")
            }
        }
    }

    private fun showArmFailedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.arm_failed_title))
            .setMessage(getString(R.string.arm_failed_body))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
    }

    private fun showDisarmCriticalFailureDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.disarm_critical_failure_title))
            .setMessage(getString(R.string.disarm_critical_failure_body))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
    }

    private fun showDisarmPartialFailureDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.disarm_partial_failure_title))
            .setMessage(getString(R.string.disarm_partial_failure_body))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
    }
}