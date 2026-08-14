package cvc.dashingdog.brandwag.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import cvc.dashingdog.brandwag.data.model.BurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Runs on a real device/emulator - Android's filesystem does atomic
 * rename-over-existing-file correctly (unlike Windows' File.renameTo), so
 * these write-after-write scenarios are only meaningful here, not as JVM
 * unit tests. See BurnStateRepositoryTest.kt for the companion read-only test.
 */
@RunWith(AndroidJUnit4::class)
class BurnStateRepositoryInstrumentedTest {

    private val today = LocalDate.of(2026, 8, 13)
    private val yesterday = today.minusDays(1)

    private fun testFile(name: String): File {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "datastoreInstrumentedTests").apply { mkdirs() }
        val file = File(dir, name)
        file.deleteOnExit()
        return file
    }

    private fun newDataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    @Test
    fun setArmedResolvesThenApplies_staleStoredStateNeverPersistedMidWrite() = runTest {
        val file = testFile("burn_state_test_${System.nanoTime()}.preferences_pb")

        val scope1 = CoroutineScope(Job())
        val repo1 = BurnStateRepository(newDataStore(file, scope1))
        repo1.setArmed(requestedArmed = true, today = yesterday)
        scope1.cancel()

        val scope2 = CoroutineScope(Job())
        val repo2 = BurnStateRepository(newDataStore(file, scope2))
        val result = repo2.setArmed(requestedArmed = false, today = today)
        scope2.cancel()

        assertEquals(BurnState.IDLE, result)

        val scope3 = CoroutineScope(Job())
        val reread = BurnStateRepository(newDataStore(file, scope3)).getBurnState(today = today)
        scope3.cancel()
        assertEquals(BurnState.IDLE, reread)
    }

    @Test
    fun stateWrittenBeforeSimulatedProcessDeath_isReadableAfterRecreation() = runTest {
        val file = testFile("burn_state_test_${System.nanoTime()}.preferences_pb")

        val scope1 = CoroutineScope(Job())
        val repo1 = BurnStateRepository(newDataStore(file, scope1))
        repo1.setArmed(requestedArmed = true, today = today)
        scope1.cancel()

        val scope2 = CoroutineScope(Job())
        val repo2 = BurnStateRepository(newDataStore(file, scope2))
        val result = repo2.getBurnState(today = today)
        scope2.cancel()

        assertEquals(BurnState(armed = true, armedDate = today), result)
    }

    @Test
    fun rebootScenario_armedStateAndArmedDateBothSurviveAFreshDataStoreInstance() = runTest {
        val file = testFile("burn_state_test_${System.nanoTime()}.preferences_pb")

        val scope1 = CoroutineScope(Job())
        val repo1 = BurnStateRepository(newDataStore(file, scope1))
        repo1.setArmed(requestedArmed = true, today = today)
        scope1.cancel()

        val scope2 = CoroutineScope(Job())
        val repo2 = BurnStateRepository(newDataStore(file, scope2))
        val resolvedSameDay = repo2.getBurnState(today = today)
        scope2.cancel()

        assertEquals(true, resolvedSameDay.armed)
        assertEquals(today, resolvedSameDay.armedDate)
    }
}