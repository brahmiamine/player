package fr.streamia.tv.ui

import fr.streamia.tv.data.ReleaseInfo
import fr.streamia.tv.data.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDialogContentTest {
    private val release = ReleaseInfo(version = "build 300", htmlUrl = "", notes = "build 300")

    @Test
    fun downloadShowsRealProgressAndCanBeHiddenWithoutCancelling() {
        val content = updateDialogContent(checking = true, result = UpdateCheckResult.Downloading(release, 0.42f), currentVersion = "build 290")
        assertEquals(0.42f, content.progress)
        assertEquals(listOf(UpdateStepState.Active, UpdateStepState.Pending, UpdateStepState.Pending), content.steps)
        assertNull(content.primary)
        assertFalse(content.closeClearsState)
    }

    @Test
    fun missingPermissionOffersToOpenTheSettingAndKeepsTheUpdate() {
        val content = updateDialogContent(checking = false, result = UpdateCheckResult.AwaitingInstallPermission(release), currentVersion = "build 290")
        assertEquals(UpdateDialogAction.OpenPermission, content.primary)
        assertEquals(UpdateStepState.Active, content.steps!![1])
        assertFalse(content.closeClearsState)
    }

    @Test
    fun failedInstallWithDownloadedApkRetriesOnlyTheInstall() {
        val content = updateDialogContent(checking = false, result = UpdateCheckResult.Error("Échec", release), currentVersion = "build 290")
        assertEquals(UpdateDialogAction.RetryInstall, content.primary)
        assertEquals(UpdateStepState.Failed, content.steps!![2])
        assertTrue(content.closeClearsState)
    }

    @Test
    fun failedCheckRetriesTheWholeUpdate() {
        val content = updateDialogContent(checking = false, result = UpdateCheckResult.Error("Pas de réseau"), currentVersion = "build 290")
        assertEquals(UpdateDialogAction.RetryCheck, content.primary)
    }
}
