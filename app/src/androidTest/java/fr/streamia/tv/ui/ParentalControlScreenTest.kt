package fr.streamia.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.streamia.tv.ui.theme.StreamiaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests d'interface du contrôle parental : définition d'un code (saisie + confirmation), changement
 * de code existant et désactivation. Le flux complet du clavier numérique est piloté sans dépôt.
 */
class ParentalControlScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setScreen(
        enabled: Boolean,
        onSetPin: (String) -> Unit = {},
        onVerifyPin: suspend (String) -> Boolean = { true },
        onDisable: () -> Unit = {},
    ) {
        composeRule.setContent {
            StreamiaTheme {
                ParentalControlScreen(
                    enabled = enabled,
                    onSetPin = onSetPin,
                    onVerifyPin = onVerifyPin,
                    onDisable = onDisable,
                    onBack = {},
                )
            }
        }
    }

    private fun typePin(vararg digits: String) {
        digits.forEach { digit -> composeRule.onNodeWithText(digit).performClick() }
    }

    /** Le verdict d'un code passe par une coroutine : attend qu'un texte apparaisse. */
    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun disabledScreenOffersDefineCodeOnly() {
        setScreen(enabled = false)
        composeRule.onNodeWithText("Définir un code").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Désactiver").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun enabledScreenOffersChangeAndDisable() {
        setScreen(enabled = true)
        composeRule.onNodeWithText("Changer le code").assertIsDisplayed()
        composeRule.onNodeWithText("Désactiver").assertIsDisplayed()
    }

    @Test
    fun defineCodeFlowCallsOnSetPinWithConfirmedPin() {
        var defined: String? = null
        setScreen(enabled = false, onSetPin = { defined = it })

        composeRule.onNodeWithText("Définir un code").performClick()
        composeRule.onNodeWithText("Nouveau code").assertIsDisplayed()
        typePin("1", "2", "3", "4")

        waitForText("Confirmez le code")
        typePin("1", "2", "3", "4")

        composeRule.waitUntil(timeoutMillis = 5_000) { defined != null }
        assertEquals("1234", defined)
    }

    @Test
    fun mismatchedConfirmationDoesNotCallOnSetPin() {
        var defined: String? = null
        setScreen(enabled = false, onSetPin = { defined = it })

        composeRule.onNodeWithText("Définir un code").performClick()
        typePin("1", "2", "3", "4")
        // Confirmation différente : onSetPin ne doit pas être appelé, le champ se vide.
        waitForText("Confirmez le code")
        typePin("5", "6", "7", "8")

        waitForText("Code incorrect — après 5 erreurs, patientez avant de réessayer")
        assertEquals(null, defined)
    }

    @Test
    fun disableFlowVerifiesCurrentPinThenDisables() {
        var disabled = false
        setScreen(enabled = true, onVerifyPin = { it == "1234" }, onDisable = { disabled = true })

        composeRule.onNodeWithText("Désactiver").performClick()
        composeRule.onNodeWithText("Désactiver le contrôle parental").assertIsDisplayed()
        typePin("1", "2", "3", "4")

        composeRule.waitUntil(timeoutMillis = 5_000) { disabled }
        assertTrue(disabled)
    }
}
