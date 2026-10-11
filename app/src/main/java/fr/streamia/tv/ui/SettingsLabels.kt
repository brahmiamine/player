package fr.streamia.tv.ui

import fr.streamia.tv.data.BufferMode
import fr.streamia.tv.data.DisplayModeSwitch
import fr.streamia.tv.data.LiveChannelSortOrder
import fr.streamia.tv.data.LiveStreamFormat
import fr.streamia.tv.data.PrayerMethod
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.data.VideoAspectSetting
import fr.streamia.tv.data.VodSortOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Libellés des réglages et petites aides de formatage des Paramètres.

internal fun prayerMethodLabel(method: PrayerMethod): String = when (method) {
    PrayerMethod.MuslimWorldLeague -> "Ligue islamique mondiale"
    PrayerMethod.France -> "France (UOIF, 12°)"
    PrayerMethod.Tunisia -> "Tunisie (18°)"
    PrayerMethod.Egypt -> "Égypte"
    PrayerMethod.UmmAlQura -> "Umm al-Qura (La Mecque)"
    PrayerMethod.Karachi -> "Karachi"
    PrayerMethod.NorthAmerica -> "Amérique du Nord (ISNA)"
}

/** Date d'expiration d'un compte fournisseur (Paramètres et message de test de connexion). */
internal fun formatExpiry(epochSeconds: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000L))

internal fun <T> cycleTo(values: List<T>, current: T, targetIndex: Int, onCycle: () -> Unit) {
    if (values.isEmpty() || targetIndex !in values.indices) return
    val currentIndex = values.indexOf(current).takeIf { it >= 0 } ?: 0
    val steps = (targetIndex - currentIndex + values.size) % values.size
    repeat(steps) { onCycle() }
}

internal fun previewDelayLabel(delayMs: Int): String = when {
    delayMs <= 0 -> "Immédiat"
    delayMs < 1_000 -> delayMs.toString() + " ms"
    delayMs % 1_000 == 0 -> (delayMs / 1_000).toString() + " s"
    else -> (delayMs / 1_000.0).toString() + " s"
}

internal fun videoAspectLabel(value: VideoAspectSetting): String = when (value) {
    VideoAspectSetting.Fit -> "Ajuster"
    VideoAspectSetting.Fill -> "Remplir"
    VideoAspectSetting.Zoom -> "Zoom"
}

internal fun liveStreamFormatLabel(value: LiveStreamFormat): String = when (value) {
    LiveStreamFormat.Auto -> "Automatique"
    LiveStreamFormat.Ts -> "MPEG-TS"
    LiveStreamFormat.Hls -> "HLS"
}

internal fun displayModeSwitchLabel(value: DisplayModeSwitch): String = when (value) {
    DisplayModeSwitch.Off -> "Désactivé"
    DisplayModeSwitch.Vod -> "Films et séries"
    DisplayModeSwitch.All -> "Films, séries et direct"
}

internal fun bufferModeLabel(value: BufferMode): String = when (value) {
    BufferMode.LowLatency -> "Faible latence"
    BufferMode.Auto -> "Automatique"
    BufferMode.Stable -> "Stable"
}

internal fun liveSortLabel(value: LiveChannelSortOrder): String = when (value) {
    LiveChannelSortOrder.Provider -> "Ordre du fournisseur"
    LiveChannelSortOrder.Number -> "Par numéro"
    LiveChannelSortOrder.Alphabetical -> "Alphabétique"
}

internal fun vodSortLabel(value: VodSortOrder): String = when (value) {
    VodSortOrder.Provider -> "Ordre du fournisseur"
    VodSortOrder.Alphabetical -> "Alphabétique"
    VodSortOrder.RecentlyAdded -> "Récemment ajoutés"
    VodSortOrder.Rating -> "Mieux notés"
}

internal fun epgOffsetLabel(value: Int): String =
    if (value == 0) "Aucun" else (if (value > 0) "+" else "") + value.toString() + " h"

internal fun subtitleScaleLabel(value: Float): String =
    "×" + "%.2f".format(value).trimEnd('0').trimEnd('.')

internal fun backupFileName(): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.FRANCE).format(Date())
    return "streamia-sauvegarde-" + stamp + ".json"
}

internal fun updateSubtitle(currentVersion: String, checking: Boolean, result: UpdateCheckResult?): String = when (result) {
    null -> if (checking) "Recherche en cours…" else "Version actuelle : $currentVersion"
    is UpdateCheckResult.UpdateAvailable -> "Nouvelle version " + result.release.version
    is UpdateCheckResult.Downloading -> "Téléchargement" + (result.progress?.let { " " + (it * 100).toInt() + " %" } ?: "…")
    is UpdateCheckResult.Downloaded -> "Prête à installer · " + result.release.version
    is UpdateCheckResult.AwaitingInstallPermission -> "Autorisation d'installation requise"
    is UpdateCheckResult.Installing -> "Installation en cours…"
    is UpdateCheckResult.UpToDate -> "À jour · version $currentVersion"
    is UpdateCheckResult.NoTaggedRelease -> "Aucune version publiée"
    is UpdateCheckResult.Error -> result.message
}

/** Description courte affichée sous le titre d'une ligne de réglage ; null = pas de description. */
internal fun settingsRowDescription(title: String): String? = when (title) {
    "Ville (météo & prières)" -> "Utilisée par la météo et les heures de prière"
    "Calcul des prières" -> "Méthode suivie par ta mosquée ou ton pays"
    "Blocs de l'accueil" -> "Choisis les rangées affichées"
    "Contrôle parental" -> "Verrouille des catégories avec un code"
    "Aperçu TV en direct" -> "Démarre un aperçu quand tu parcours les chaînes"
    "Délai de l'aperçu" -> "Réduit les lancements de flux pendant une navigation rapide"
    "Avance / retour VOD" -> "Pas des actions d'avance et de retour"
    "Format vidéo" -> "Comment l'image remplit l'écran"
    "Adapter l'affichage" -> "Aligne la résolution et la fréquence de l'écran sur la vidéo"
    "Mode tunnel (4K HDR)" -> "Lecture matérielle directe sur les TV compatibles"
    "Format du flux Live" -> "Automatique est recommandé"
    "Stabilité du flux" -> "Latence faible ou lecture plus stable"
    "Secours automatique" -> "Bascule sur une autre version de la chaîne si le flux échoue"
    "Tri des chaînes" -> "Ordre des chaînes en direct"
    "Tri Films / Séries" -> "Ordre des contenus VOD"
    "Décalage horaire EPG" -> "Corrige un guide décalé par rapport à ta région"
    "Épisode suivant auto." -> "Enchaîne les épisodes d'une série"
    "Taille des sous-titres" -> "Échelle du texte affiché"
    "Fond des sous-titres" -> "Bandeau sombre derrière le texte"
    "Recherche" -> "Chaînes, films et séries"
    "Guide TV" -> "EPG et grille des chaînes"
    "Organiser" -> "Catégories et contenus"
    "Actualiser", "Actualisation…" -> "Recharge la liste et le catalogue"
    "Mises à jour" -> "Version de l'application"
    "À propos" -> "Version, appareil et cache"
    "Sauvegarder les réglages" -> "Exporter les préférences"
    "Restaurer les réglages" -> "Importer une sauvegarde"
    "Assistant IA" -> "Active les fonctions IA de l'appli"
    "Fournisseur" -> "Service qui répond aux requêtes"
    "Clé d'API" -> "Stockée chiffrée sur l'appareil"
    "Modèle" -> "Liste fournie par le fournisseur"
    "Langue des descriptions" -> "Traduction des résumés de films et séries"
    "Consommation IA" -> "Requêtes, tokens, quotas"
    "Sous-titres bilingues" -> "Affiche l'original sous la traduction"
    "Pré-calcul de nuit" -> "Prépare traductions et collections quand la TV est au repos"
    else -> null
}
