package fr.streamia.tv.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import fr.streamia.tv.data.PlaylistKind
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.ui.theme.StreamiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Tests d'interface de l'écran de connexion : liste des profils, ouverture du formulaire Xtream,
 * validation des champs et confirmation de suppression. Le dépôt n'est pas nécessaire ici — l'écran
 * ne reçoit que de l'état et des rappels.
 */
class LoginScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun profile(id: String, name: String, kind: PlaylistKind) = PlaylistProfile(
        id = id, name = name, kind = kind,
        serverUrl = if (kind == PlaylistKind.Xtream) "http://srv" else null,
        username = if (kind == PlaylistKind.Xtream) "user" else null,
        password = if (kind == PlaylistKind.Xtream) "pass" else null,
    )

    private fun setLoginScreen(
        profiles: List<PlaylistProfile> = emptyList(),
        busy: Boolean = false,
        message: String? = null,
        onOpenProfile: (String) -> Unit = {},
        onSignIn: (String?, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
        onTestConnection: (String, String, String) -> Unit = { _, _, _ -> },
        onDeleteProfile: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            StreamiaTheme {
                LoginScreen(
                    profiles = profiles,
                    busy = busy,
                    testingConnection = false,
                    testSucceeded = false,
                    message = message,
                    onOpenProfile = onOpenProfile,
                    onSignIn = onSignIn,
                    onTestConnection = onTestConnection,
                    onImportM3u = { _: Uri, _: String?, _: String -> },
                    onImportM3uUrl = { _: String?, _: String, _: String, _: String, _: Int -> },
                    onSaveM3uSettings = { _: String, _: String, _: String, _: Int -> },
                    onRenameProfile = { _: String, _: String -> },
                    onDeleteProfile = onDeleteProfile,
                    onDismissMessage = {},
                )
            }
        }
    }

    @Test
    fun emptyManagerShowsGuidance() {
        setLoginScreen()
        composeRule.onNodeWithText("Mes listes").assertIsDisplayed()
        composeRule.onNodeWithText("Aucune liste enregistrée. Ajoutez votre première source.").assertIsDisplayed()
    }

    @Test
    fun profilesAreListedWithTheirKind() {
        setLoginScreen(profiles = listOf(profile("p1", "Ma liste", PlaylistKind.Xtream)))
        composeRule.onNodeWithText("Ma liste").assertIsDisplayed()
        composeRule.onNodeWithText("XTREAM").assertIsDisplayed()
    }

    @Test
    fun openingXtreamFormShowsItsFields() {
        setLoginScreen()
        composeRule.onNodeWithText("＋ Ajouter Xtream").performClick()
        composeRule.onNodeWithText("Ajouter une liste Xtream").assertIsDisplayed()
        composeRule.onNodeWithText("Enregistrer et ouvrir").assertIsDisplayed()
    }

    @Test
    fun savingXtreamCallsOnSignInWithEnteredCredentials() {
        var captured: Pair<String?, List<String>>? = null
        setLoginScreen(onSignIn = { profileId, name, server, user, pass ->
            captured = profileId to listOf(name, server, user, pass)
        })

        composeRule.onNodeWithText("＋ Ajouter Xtream").performClick()
        // Champs : 0 = nom, 1 = serveur, 2 = identifiant, 3 = mot de passe.
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[1].performTextInput("http://serveur.test")
        fields[2].performTextInput("utilisateur")
        fields[3].performTextInput("secret")

        composeRule.onNodeWithText("Enregistrer et ouvrir").performClick()

        assertEquals(null, captured?.first)
        assertEquals(
            listOf("", "http://serveur.test", "utilisateur", "secret"),
            captured?.second,
        )
    }

    @Test
    fun testConnectionCallsOnTestConnectionWithEnteredCredentials() {
        var captured: Triple<String, String, String>? = null
        setLoginScreen(onTestConnection = { server, user, pass -> captured = Triple(server, user, pass) })

        composeRule.onNodeWithText("＋ Ajouter Xtream").performClick()
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[1].performTextInput("http://serveur.test")
        fields[2].performTextInput("utilisateur")
        fields[3].performTextInput("secret")

        composeRule.onNodeWithText("Tester la connexion").performClick()

        assertEquals(Triple("http://serveur.test", "utilisateur", "secret"), captured)
    }

    @Test
    fun deleteConfirmationCallsOnDeleteProfile() {
        var deleted: String? = null
        setLoginScreen(
            profiles = listOf(profile("p1", "Ma liste", PlaylistKind.Xtream)),
            onDeleteProfile = { deleted = it },
        )

        composeRule.onAllNodesWithText("Supprimer").onFirst().performClick()
        composeRule.onNodeWithText("Supprimer « Ma liste » ?").assertIsDisplayed()

        composeRule.onAllNodesWithText("Supprimer").onLast().performClick()

        assertEquals("p1", deleted)
    }

    @Test
    fun openingAProfileCallsOnOpenProfile() {
        var opened: String? = null
        setLoginScreen(
            profiles = listOf(profile("p1", "Ma liste", PlaylistKind.Xtream)),
            onOpenProfile = { opened = it },
        )

        composeRule.onNodeWithText("Ma liste").performClick()

        assertEquals("p1", opened)
    }
}
