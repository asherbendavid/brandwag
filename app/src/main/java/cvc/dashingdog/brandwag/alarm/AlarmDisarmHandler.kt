package cvc.dashingdog.brandwag.alarm

import android.content.Context
import cvc.dashingdog.brandwag.data.repository.SnoozeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Per Chris's 4b planning rule: "If burn state is disarmed, the alarm and
 * pending snoozes can be cancelled." This is that cancellation, extracted
 * so it can be called from a single place regardless of what UI eventually
 * triggers a disarm.
 *
 * NOT YET WIRED to anything real - Brandwag has no burn-toggle UI yet (see
 * REQUIREMENTS.md, still pending a later phase). Whichever phase builds
 * BurnStateRepository.setArmed(false) into a real UI action MUST call
 * onDisarmed() from that same path, or this rule silently doesn't hold.
 * Flagging explicitly here rather than letting it become an assumed gap.
 */
object AlarmDisarmHandler {

    fun onDisarmed(context: Context) {
        AlarmScheduler.cancelSnooze(context)
        AlarmForegroundService.stop(context) // no-op via the service's own idempotency guard if nothing is sounding

        CoroutineScope(Dispatchers.IO).launch {
            SnoozeRepository(context).clear()
        }
    }
}