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
