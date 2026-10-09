package fr.streamia.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.ui.theme.StreamiaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests d'interface de l'écran Paramètres : les tuiles d'action principale déclenchent bien leurs
 * rappels. Le dépôt n'est pas nécessaire — l'écran ne fait qu'afficher [AppSettings] et notifier.
 */
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setScreen(
        onToggleLivePreview: () -> Unit = {},
        onAbout: () -> Unit = {},
    ) {
        composeRule.setContent {
            StreamiaTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    playlistName = "Ma liste",
                    accountExpiresAtEpochSeconds = null,
                    detectedPlaceName = null,
                    busy = false,
                    liveHistoryCount = 0,
                    movieHistoryCount = 0,
                    seriesHistoryCount = 0,
                    currentVersion = "1.5.8",
                    updateChecking = false,
                    updateCheck = null as UpdateCheckResult?,
                    onToggleLivePreview = onToggleLivePreview,
                    onCycleLivePreviewDelay = {},
                    onCycleVodSeekStep = {},
                    onCycleVideoAspect = {},
                    onCycleBufferMode = {},
                    onCycleDisplayModeSwitch = {},
                    onToggleTunneling = {},
                    onCycleLiveStreamFormat = {},
                    onCycleLiveChannelSortOrder = {},
                    onCycleVodSortOrder = {},
                    onCycleEpgTimeOffset = {},
                    onToggleAutoPlayNextEpisode = {},
                    onToggleLiveVersionFailover = {},
                    onCycleSubtitleSizeScale = {},
                    onToggleSubtitleBackground = {},
                    onToggleHomeBlock = { _: HomeBlock -> },
                    onSearch = {},
                    onEpg = {},
                    onOrganizer = {},
                    onRefresh = {},
                    onClearLiveHistory = {},
                    onClearMovieHistory = {},
                    onClearSeriesHistory = {},
                    onClearAllHistory = {},
                    onChangePlaylist = {},
                    onCheckForUpdate = {},
                    onDismissUpdateCheck = {},
                    onInstallUpdate = {},
                    onAllowUpdateInstall = {},
                    onExportBackup = { "" },
                    onImportBackup = { "" },
                    onAbout = onAbout,
                    onParentalControl = {},
                    onSearchCities = { _: String -> emptyList<HomePlace>() },
                    onSetHomePlace = { _: HomePlace? -> },
                    onSetPrayerMethod = { _: PrayerMethod -> },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun livePreviewTileCallsOnToggleLivePreview() {
        var toggled = false
        setScreen(onToggleLivePreview = { toggled = true })

        composeRule.onNodeWithText("Lecture").performClick()
        composeRule.onNodeWithText("Aperçu TV en direct").performScrollTo().performClick()
        assertTrue(toggled)
    }

    @Test
    fun aboutTileCallsOnAbout() {
        var opened = false
        setScreen(onAbout = { opened = true })

        composeRule.onNodeWithText("Données & application").performClick()
        composeRule.onNodeWithText("À propos").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun screenRendersTitle() {
        setScreen()
        composeRule.onNodeWithText("Paramètres").assertIsDisplayed()
    }
}
