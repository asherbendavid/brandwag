package cvc.dashingdog.brandwag.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cvc.dashingdog.brandwag.data.model.BurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class BurnStateRepositoryTest {

    private val today = LocalDate.of(2026, 8, 13)
    private val yesterday = today.minusDays(1)

    private fun testFile(name: String): File {
        val dir = File("build/tmp/datastoreTests").apply { mkdirs() }
        val file = File(dir, name)
        file.deleteOnExit()
        return file
    }

    private fun copyToNewFile(source: File): File {
        val dest = testFile("burn_state_reopen_${System.nanoTime()}.preferences_pb")
        source.copyTo(dest, overwrite = true)
        return dest
    }

    private fun newDataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    private suspend fun <T> retryOnTransientIOException(times: Int = 5, block: suspend () -> T): T {
        repeat(times - 1) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                if (e.message?.contains("Unable to rename") != true) throw e
                kotlinx.coroutines.delay(50)
            }
        }
        return block()
    }

    @Test
    fun `getBurnState resolves rollover on read without mutating storage`() = runTest {
        val file1 = testFile("burn_state_test_${System.nanoTime()}.preferences_pb")

        val scope1 = CoroutineScope(Job())
        val repo1 = BurnStateRepository(newDataStore(file1, scope1))
        repo1.setArmed(requestedArmed = true, today = yesterday)
        scope1.cancel()

        val file2 = copyToNewFile(file1)
        val scope2 = CoroutineScope(Job())
        val repo2 = BurnStateRepository(newDataStore(file2, scope2))
        val resolved = repo2.getBurnState(today = today)
        scope2.cancel()

        assertEquals(BurnState.IDLE, resolved)
    }

    /* @Test // Test moved to instrumented test. Because of an OS level file handling inconsistency between Linux (Android) and Windows, this test in JVM always fails.
    fun `setArmed resolves-then-applies - stale stored state never persisted mid-write`() = runTest {
        val file1 = testFile("burn_state_test_${System.nanoTime()}.preferences_pb")

        val scope1 = CoroutineScope(Job())
        val repo1 = BurnStateRepository(newDataStore(file1, scope1))
        repo1.setArmed(requestedArmed = true, today = yesterday)
        scope1.cancel()

        val file2 = copyToNewFile(file1)
        val scope2 = CoroutineScope(Job())
        val repo2 = BurnStateRepository(newDataStore(file2, scope2))
        val result = repo2.setArmed(requestedArmed = false, today = today)
        scope2.cancel()

        assertEquals(BurnState.IDLE, result)

        val file3 = copyToNewFile(file2)
        val scope3 = CoroutineScope(Job())
        val reread = BurnStateRepository(newDataStore(file3, scope3)).getBurnState(today = today)
        scope3.cancel()
        assertEquals(BurnState.IDLE, reread)
    } */

    @Test
    fun `state written before simulated process death is readable after recreation`() = runTest {
        val file1 = testFile("burn_state_test_${System.nanoTime()}.preferences_pb")

        val scope1 = CoroutineScope(Job())
        val repo1 = BurnStateRepository(newDataStore(file1, scope1))
        repo1.setArmed(requestedArmed = true, today = today)
        scope1.cancel()

        val file2 = copyToNewFile(file1)
        val scope2 = CoroutineScope(Job())
        val repo2 = BurnStateRepository(newDataStore(file2, scope2))
        val result = repo2.getBurnState(today = today)
        scope2.cancel()

        assertEquals(BurnState(armed = true, armedDate = today), result)
    }

    @Test
    fun `reboot scenario - armed state and armedDate both survive a fresh DataStore instance`() = runTest {
        val file1 = testFile("burn_state_test_${System.nanoTime()}.preferences_pb")

        val scope1 = CoroutineScope(Job())
        val repo1 = BurnStateRepository(newDataStore(file1, scope1))
        repo1.setArmed(requestedArmed = true, today = today)
        scope1.cancel()

        val file2 = copyToNewFile(file1)
        val scope2 = CoroutineScope(Job())
        val repo2 = BurnStateRepository(newDataStore(file2, scope2))
        val resolvedSameDay = repo2.getBurnState(today = today)
        scope2.cancel()

        assertEquals(true, resolvedSameDay.armed)
        assertEquals(today, resolvedSameDay.armedDate)
    }
}