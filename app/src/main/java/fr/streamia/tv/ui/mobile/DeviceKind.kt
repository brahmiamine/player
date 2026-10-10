package fr.streamia.tv.ui.mobile

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf
import fr.streamia.tv.ui.StreamiaScreen

/** Type d'appareil qui affiche l'application : la TV se pilote à la télécommande, le mobile au doigt. */
enum class DeviceKind { Tv, Mobile }

/**
 * Détection au lancement : Android TV (mode d'interface « télévision » ou fonction Leanback) ou
 * téléphone/tablette. Une tablette est traitée comme un mobile : elle se pilote au doigt.
 */
fun detectDeviceKind(context: Context): DeviceKind {
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val television = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return if (television) DeviceKind.Tv else DeviceKind.Mobile
}

val LocalDeviceKind = staticCompositionLocalOf { DeviceKind.Tv }

/**
 * Écrans déjà refaits pour le mobile (portrait, densité réelle, barre d'onglets). Les autres
 * gardent pour l'instant la mise en page TV adaptée au téléphone, en paysage.
 */
fun StreamiaScreen.isMobileNative(): Boolean = when (this) {
    StreamiaScreen.Home, StreamiaScreen.Browser, StreamiaScreen.More,
    StreamiaScreen.Login, StreamiaScreen.Search, StreamiaScreen.LiveMatches, StreamiaScreen.Settings,
    StreamiaScreen.Epg, StreamiaScreen.Organizer -> true
    is StreamiaScreen.MovieDetails, is StreamiaScreen.Series -> true
    else -> false
}

/** Écrans qui portent la barre d'onglets du bas (Accueil, Direct, Films, Séries, Plus). */
fun StreamiaScreen.hasMobileTabBar(): Boolean = when (this) {
    StreamiaScreen.Home, StreamiaScreen.Browser, StreamiaScreen.More -> true
    else -> false
}
