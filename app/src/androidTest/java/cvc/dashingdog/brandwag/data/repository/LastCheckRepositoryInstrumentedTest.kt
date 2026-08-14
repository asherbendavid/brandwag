package cvc.dashingdog.brandwag.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cvc.dashingdog.brandwag.data.model.CheckOutcome
import cvc.dashingdog.brandwag.data.model.RawCheckValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Covers the one LastCheckRepository scenario needing two genuine writes to
 * the same file - same Windows File.renameTo limitation as
 * BurnStateRepositoryInstrumentedTest. See that file's header comment for
 * the full explanation.
 */
@RunWith(AndroidJUnit4::class)
class LastCheckRepositoryInstrumentedTest {

    private fun testFile(name: String): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "datastoreInstrumentedTests").apply { mkdirs() }
        val file = File(dir, name)
        file.deleteOnExit()
        return file
    }

    private fun newDataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    private fun rawValues(gust: Double? = 42.0) = RawCheckValues(
        gustsByModel = mapOf("ecmwf_ifs025" to gust),
        sustainedWindByModel = emptyMap()
    )

    @Test
    fun recordOutcomeWithNewerTimestamp_overwritesAndReturnsTrue() = runTest {
        val file = testFile("last_check_test_${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(Job())
        val repo = LastCheckRepository(newDataStore(file, scope))

        val first = CheckOutcome.Clear(3, rawValues(), Instant.parse("2026-08-13T08:00:00Z"))
        val second = CheckOutcome.Dangerous(4, rawValues(55.0), Instant.parse("2026-08-13T09:00:00Z"))

        assertTrue(repo.recordOutcome(first))
        assertTrue(repo.recordOutcome(second))

        val result = repo.getLastCheck()
        assertTrue(result is CheckOutcome.Dangerous)

        scope.cancel()
    }
}