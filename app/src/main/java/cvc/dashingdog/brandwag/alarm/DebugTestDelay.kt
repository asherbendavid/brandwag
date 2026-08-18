package cvc.dashingdog.brandwag.alarm

import kotlinx.coroutines.delay

/**
 * TESTING AID ONLY - not part of Brandwag's real behavior.
 *
 * Lets a real device failure (battery pull) be timed precisely against a
 * known checkpoint in the code, for exercising 4d's boot-recovery branches
 * that can't be simulated any other way (a genuine mid-write process death,
 * not just an app-switcher kill).
 *
 * Usage: call mark("descriptive label") at a point of interest. It logs the
 * label, then waits DELAY_MS - pull the battery any time during that window
 * to test what happens if the process dies at that exact point.
 *
 * TO DISABLE WHEN TESTING CONCLUDES: flip ENABLED to false below. Every call
 * site stays in the code (no need to hunt down and remove each one) but
 * becomes a no-op immediately.
 */
object DebugTestDelay {

    private const val ENABLED = false // <-- delay behavior only; logging always happens regardless
    private const val DELAY_MS = 3000L

    /**
     * Always logs the checkpoint being reached, regardless of ENABLED - this is
     * what tells us a write was actually attempted, independent of test mode.
     * Only the artificial wait is conditional on ENABLED. (Bug found 2026-08-19:
     * these were previously bundled together, so disabling the delay silently
     * suppressed the confirming log too - a checkpoint that never appeared in
     * Logcat looked identical to "write never attempted," when it may only have
     * meant "delay mode was off." Split apart to remove that ambiguity.)
     */
    suspend fun mark(label: String) {
        if (ENABLED) {
            android.util.Log.w("DebugTestDelay", "$label - waiting ${DELAY_MS}ms (pull battery now to test this checkpoint)")
            delay(DELAY_MS)
        } else {
            android.util.Log.i("DebugTestDelay", "$label - reached (delay disabled)")
        }
    }
}