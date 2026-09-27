package fr.streamia.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.streamia.tv.ui.theme.StreamiaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests d'interface de l'écran « À propos » : version affichée, tailles de cache résolues et retour.
 */
class AboutScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsVersionAndResolvesCacheSizes() {
        composeRule.setContent {
            StreamiaTheme {
                AboutScreen(
                    versionName = "1.5.8",
                    onLoadCacheSize = { 2_000L },
                    onLoadEpgCacheSize = { 500L },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("À propos").assertIsDisplayed()
        composeRule.onNodeWithText("1.5.8").assertIsDisplayed()
        // Les tailles sont résolues par LaunchedEffect puis affichées ("2 Ko", "500 o").
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("2 Ko")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("2 Ko").assertIsDisplayed()
        composeRule.onNodeWithText("500 o").assertIsDisplayed()
    }

    @Test
    fun backInvokesOnBack() {
        var backed = false
        composeRule.setContent {
            StreamiaTheme {
                AboutScreen(
                    versionName = "1.5.8",
                    onLoadCacheSize = { 0L },
                    onLoadEpgCacheSize = { 0L },
                    onBack = { backed = true },
                )
            }
        }

        composeRule.onNodeWithText("← Retour").performClick()
        assertTrue(backed)
    }
}
