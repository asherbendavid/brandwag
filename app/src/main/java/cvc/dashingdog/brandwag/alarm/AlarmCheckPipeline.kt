package cvc.dashingdog.brandwag.alarm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Simulated stand-in for "fetch + TriggerLogic.evaluate() + recordOutcome()
 * + maybe start the alarm," scoped narrowly to prove out 4c's concurrency
 * fix ahead of Phase 5 wiring in the real fetch/evaluate chain.
 *
 * Design (per Chris's catch during 4c planning): evaluation is NOT gated -
 * every trigger runs its (simulated) fetch/decide step concurrently, so a
 * slow or malformed trigger can never block a genuine alarm behind it. Only
 * the short "read latest state -> decide to start the alarm -> commit" step
 * is serialized, via a Mutex rather than WorkManager uniqueness - because
 * this step is fast (no network call inside the lock) a coroutine Mutex is
 * the right-sized tool, and it can't be starved by a concurrent evaluation
 * since evaluation finishes BEFORE a trigger ever queues for the lock.
 *
 * AlarmForegroundService.startAlarm()'s own idempotency guard (4c) is a
 * second, independent line of defense if this is ever misused.
 */
object AlarmCheckPipeline {

    private val commitMutex = Mutex()
    private val pipelineScope = CoroutineScope(Dispatchers.Default)

    /**
     * Simulates one trigger through the pipeline.
     *
     * @param simulateMalformed if true, this trigger evaluates to InsufficientData
     *   instead of a gust reading.
     * @param simulateGustKmh the gust value a "good data" trigger should evaluate to.
     * @param evaluateDelayMs override for how long the (fake) evaluation phase takes.
     *   Defaults to a wide, easy-to-pass gap (3s malformed / ~200-600ms good) if null -
     *   pass an explicit small value (e.g. 5-10ms) on BOTH triggers to force a genuine
     *   near-simultaneous race at the commit-mutex boundary, which a wide gap can't
     *   meaningfully exercise.
     */
    fun trigger(
        context: android.content.Context,
        label: String,
        simulateMalformed: Boolean = false,
        simulateGustKmh: Double = 65.0,
        evaluateDelayMs: Long? = null
    ) {
        pipelineScope.launch {
            val decision = evaluate(label, simulateMalformed, simulateGustKmh, evaluateDelayMs)
            commitMutex.withLock {
                commit(context, label, decision)
            }
        }
    }

    private suspend fun evaluate(
        label: String,
        simulateMalformed: Boolean,
        simulateGustKmh: Double,
        evaluateDelayMs: Long?
    ): SimulatedDecision {
        val delayMs = evaluateDelayMs ?: if (simulateMalformed) 3000L else Random.nextLong(200, 600)
        kotlinx.coroutines.delay(delayMs)
        return if (simulateMalformed) {
            SimulatedDecision.InsufficientData(label)
        } else if (simulateGustKmh >= TEST_GUST_THRESHOLD_KMH) {
            SimulatedDecision.Alarm(label, simulateGustKmh)
        } else {
            SimulatedDecision.Clear(label)
        }
    }

    /**
     * Serialized phase. Stands in for LastCheckRepository.recordOutcome()
     * (Phase 2) + conditionally starting AlarmForegroundService. Only this
     * step is mutex-gated.
     */
    private fun commit(context: android.content.Context, label: String, decision: SimulatedDecision) {
        when (decision) {
            is SimulatedDecision.Alarm -> {
                android.util.Log.i("AlarmCheckPipeline", "[$label] Alarm decision -> starting service")
                AlarmForegroundService.start(context, decision.maxGustKmh)
            }
            is SimulatedDecision.Clear -> {
                android.util.Log.i("AlarmCheckPipeline", "[$label] Clear - no action")
            }
            is SimulatedDecision.InsufficientData -> {
                android.util.Log.i("AlarmCheckPipeline", "[$label] InsufficientData - no action, must never read as Clear")
            }
        }
    }

    private sealed class SimulatedDecision {
        data class Alarm(val label: String, val maxGustKmh: Double) : SimulatedDecision()
        data class Clear(val label: String) : SimulatedDecision()
        data class InsufficientData(val label: String) : SimulatedDecision()
    }

    private const val TEST_GUST_THRESHOLD_KMH = 50.0
}