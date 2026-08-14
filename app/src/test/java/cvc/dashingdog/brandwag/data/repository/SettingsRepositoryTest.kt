package cvc.dashingdog.brandwag.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cvc.dashingdog.brandwag.data.model.BrandwagSettings
import cvc.dashingdog.brandwag.data.model.SettingsUpdateResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    private fun testFile(name: String): File {
        val dir = File("build/tmp/datastoreTests").apply { mkdirs() }
        val file = File(dir, name)
        file.deleteOnExit()
        return file
    }

    private fun newDataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    private fun newRepo(): SettingsRepository {
        val file = testFile("settings_test_${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(Job())
        return SettingsRepository(newDataStore(file, scope))
    }

    @Test
    fun `getSettings returns sane non-zero defaults on first run`() = runTest {
        val repo = newRepo()
        val settings = repo.getSettings()
        assertEquals(BrandwagSettings(), settings)
        assertTrue(settings.morningTempThreshold > 0)
        assertTrue(settings.gustLookaheadHours > 0)
    }

    @Test
    fun `updateSettings with valid values returns Success and persists`() = runTest {
        val repo = newRepo()
        val valid = BrandwagSettings(
            latitude = -33.5, longitude = 19.0,
            morningTempThreshold = 28.0, morningTempSevereThreshold = 36.0,
            morningRainSevereThreshold = 15.0, burnGustThreshold = 40.0,
            gustLookaheadHours = 6
        )
        val result = repo.updateSettings(valid)
        assertEquals(SettingsUpdateResult.Success, result)
        assertEquals(valid, repo.getSettings())
    }

    @Test
    fun `updateSettings rejects latitude out of range and returns Invalid with reason`() = runTest {
        val repo = newRepo()
        val bad = BrandwagSettings(latitude = 200.0)
        val result = repo.updateSettings(bad) as SettingsUpdateResult.Invalid
        assertTrue(result.violations.any { it.contains("Latitude") })
    }

    @Test
    fun `updateSettings rejects longitude out of range and returns Invalid with reason`() = runTest {
        val repo = newRepo()
        val bad = BrandwagSettings(longitude = -200.0)
        val result = repo.updateSettings(bad) as SettingsUpdateResult.Invalid
        assertTrue(result.violations.any { it.contains("Longitude") })
    }

    @Test
    fun `updateSettings rejects zero or negative morningTempThreshold`() = runTest {
        val repo = newRepo()
        val bad = BrandwagSettings(morningTempThreshold = 0.0)
        val result = repo.updateSettings(bad) as SettingsUpdateResult.Invalid
        assertTrue(result.violations.any { it.contains("morningTempThreshold") })
    }

    @Test
    fun `updateSettings rejects severeThreshold not more extreme than base threshold`() = runTest {
        val repo = newRepo()
        val bad = BrandwagSettings(morningTempThreshold = 35.0, morningTempSevereThreshold = 30.0)
        val result = repo.updateSettings(bad) as SettingsUpdateResult.Invalid
        assertTrue(result.violations.any { it.contains("morningTempSevereThreshold") })
    }

    @Test
    fun `updateSettings rejects zero or negative gustLookaheadHours`() = runTest {
        val repo = newRepo()
        val bad = BrandwagSettings(gustLookaheadHours = 0)
        val result = repo.updateSettings(bad) as SettingsUpdateResult.Invalid
        assertTrue(result.violations.any { it.contains("gustLookaheadHours") })
    }

    @Test
    fun `updateSettings with multiple violations returns all of them, not just the first`() = runTest {
        val repo = newRepo()
        val bad = BrandwagSettings(latitude = 200.0, gustLookaheadHours = -1, morningTempThreshold = 0.0)
        val result = repo.updateSettings(bad) as SettingsUpdateResult.Invalid
        assertTrue(result.violations.size >= 3)
    }

    @Test
    fun `rejected update does not overwrite previously valid stored settings`() = runTest {
        val repo = newRepo()
        val valid = BrandwagSettings(morningTempThreshold = 32.0)
        repo.updateSettings(valid)

        val bad = BrandwagSettings(morningTempThreshold = -5.0)
        repo.updateSettings(bad)

        val current = repo.getSettings()
        assertEquals(32.0, current.morningTempThreshold, 0.0)
    }
}