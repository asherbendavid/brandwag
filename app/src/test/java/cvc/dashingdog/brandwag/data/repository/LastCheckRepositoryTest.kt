package cvc.dashingdog.brandwag.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cvc.dashingdog.brandwag.data.model.CheckOutcome
import cvc.dashingdog.brandwag.data.model.FailureReason
import cvc.dashingdog.brandwag.data.model.RawCheckValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class LastCheckRepositoryTest {

    private class FakeLogger : Logger {
        val warnings = mutableListOf<Pair<String, String>>()
        override fun warn(tag: String, message: String) {
            warnings.add(tag to message)
        }
    }

    private fun testFile(name: String): File {
        val dir = File("build/tmp/datastoreTests").apply { mkdirs() }
        val file = File(dir, name)
        file.deleteOnExit()
        return file
    }

    private fun newDataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    private fun newRepo(logger: Logger = FakeLogger()): LastCheckRepository {
        val file = testFile("last_check_test_${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(Job())
        return LastCheckRepository(newDataStore(file, scope), logger)
        // Note: scope intentionally left running for the lifetime of this repo instance -
        // each test gets its own file + its own repo, so there's no second instance
        // contending for the same file within a single test. Scope-cancel-before-reopen
        // is only needed when we deliberately reopen the SAME file, as in the
        // stale-timestamp test below.
    }

    private fun rawValues(gust: Double? = 42.0) = RawCheckValues(
        gustsByModel = mapOf("ecmwf_ifs025" to gust),
        sustainedWindByModel = emptyMap()
    )

    @Test
    fun `recordOutcome persists Failure outcome distinctly from Degraded`() = runTest {
        val repo = newRepo()
        val outcome = CheckOutcome.Failure(
            reason = FailureReason.NetworkError("timeout"),
            timestamp = Instant.parse("2026-08-13T08:00:00Z")
        )
        repo.recordOutcome(outcome)
        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Failure)
        assertEquals("timeout", (result as CheckOutcome.Failure).reason.message)
    }

    @Test
    fun `recordOutcome persists Degraded outcome distinctly from Clear`() = runTest {
        val repo = newRepo()
        val outcome = CheckOutcome.Degraded(
            respondingModels = 2,
            rawValues = rawValues(),
            timestamp = Instant.parse("2026-08-13T08:00:00Z")
        )
        repo.recordOutcome(outcome)
        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Degraded)
        assertEquals(2, (result as CheckOutcome.Degraded).respondingModels)
    }

    @Test
    fun `recordOutcome persists Dangerous outcome distinctly from Clear`() = runTest {
        val repo = newRepo()
        val outcome = CheckOutcome.Dangerous(
            respondingModels = 4,
            rawValues = rawValues(60.0),
            timestamp = Instant.parse("2026-08-13T08:00:00Z")
        )
        repo.recordOutcome(outcome)
        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Dangerous)
    }

    /* @Test // Test moved to instrumented test, because of a OS level file handling inconsistency between Linux (Android) and Windows, causing test to always fail.
    fun `recordOutcome with newer timestamp overwrites and returns true`() = runTest {
        val repo = newRepo()
        val first = CheckOutcome.Clear(3, rawValues(), Instant.parse("2026-08-13T08:00:00Z"))
        val second = CheckOutcome.Dangerous(4, rawValues(55.0), Instant.parse("2026-08-13T09:00:00Z"))

        assertTrue(repo.recordOutcome(first))
        assertTrue(repo.recordOutcome(second))

        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Dangerous)
    } */

    @Test
    fun `recordOutcome with older timestamp is rejected, returns false, and logs stale write`() = runTest {
        val fakeLogger = FakeLogger()
        val repo = newRepo(fakeLogger)
        val newer = CheckOutcome.Clear(3, rawValues(), Instant.parse("2026-08-13T09:00:00Z"))
        val older = CheckOutcome.Dangerous(4, rawValues(55.0), Instant.parse("2026-08-13T08:00:00Z"))

        assertTrue(repo.recordOutcome(newer))
        assertFalse(repo.recordOutcome(older))

        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Clear)

        assertEquals(1, fakeLogger.warnings.size)
        assertTrue(fakeLogger.warnings[0].second.contains("Stale write rejected"))
    }

    @Test
    fun `recordOutcome with equal timestamp is rejected as stale - not treated as newer`() = runTest {
        val repo = newRepo()
        val ts = Instant.parse("2026-08-13T08:00:00Z")
        val first = CheckOutcome.Clear(3, rawValues(), ts)
        val duplicate = CheckOutcome.Dangerous(4, rawValues(55.0), ts)

        assertTrue(repo.recordOutcome(first))
        assertFalse(repo.recordOutcome(duplicate))

        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Clear)
    }

    @Test
    fun `getLastCheck returns null when nothing ever recorded`() = runTest {
        val repo = newRepo()
        assertNull(repo.getLastCheck())
    }

    @Test
    fun `Degraded outcome is never readable as a boolean success - type check only, no isSuccess field exists`() = runTest {
        val repo = newRepo()
        repo.recordOutcome(
            CheckOutcome.Degraded(2, rawValues(), Instant.parse("2026-08-13T08:00:00Z"))
        )
        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Degraded)
        assertFalse(result is CheckOutcome.Clear)
        assertFalse(result is CheckOutcome.Dangerous)
    }

    @Test
    fun `raw values with null entries in gustsByModel are preserved, not dropped, on persist and reload`() = runTest {
        val repo = newRepo()
        val raw = RawCheckValues(
            gustsByModel = mapOf(
                "ecmwf_ifs025" to 40.0,
                "gfs_seamless" to null,
                "icon_seamless" to 38.5
            ),
            sustainedWindByModel = emptyMap()
        )
        repo.recordOutcome(CheckOutcome.Degraded(2, raw, Instant.parse("2026-08-13T08:00:00Z")))

        val result = repo.getLastCheck() as CheckOutcome.Degraded
        assertEquals(3, result.rawValues.gustsByModel.size)
        assertNull(result.rawValues.gustsByModel["gfs_seamless"])
        assertEquals(40.0, result.rawValues.gustsByModel["ecmwf_ifs025"])
    }
}