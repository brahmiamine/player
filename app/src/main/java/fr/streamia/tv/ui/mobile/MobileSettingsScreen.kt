package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.bufferModeLabel
import fr.streamia.tv.ui.displayModeSwitchLabel
import fr.streamia.tv.ui.epgOffsetLabel
import fr.streamia.tv.ui.formatExpiry
import fr.streamia.tv.ui.liveSortLabel
import fr.streamia.tv.ui.liveStreamFormatLabel
import fr.streamia.tv.ui.previewDelayLabel
import fr.streamia.tv.ui.subtitleScaleLabel
import fr.streamia.tv.ui.videoAspectLabel
import fr.streamia.tv.ui.vodSortLabel
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.RadiusPill

/**
 * Paramètres au doigt : une seule liste par groupes (accueil, lecture, catalogue, sous-titres,
 * outils, application). Un interrupteur bascule tout de suite ; une valeur à choix se change en la
 * touchant (elle passe à la suivante, comme sur la TV). Ville, assistant IA, sauvegarde et mises à
 * jour restent dans [onAdvanced] (page Paramètres complète).
 */
@Composable
fun MobileSettingsScreen(
    settings: AppSettings,
    playlistName: String?,
    accountExpiresAtEpochSeconds: Long?,
    currentVersion: String,
    liveHistoryCount: Int,
    movieHistoryCount: Int,
    seriesHistoryCount: Int,
    onToggleLivePreview: () -> Unit,
    onCycleLivePreviewDelay: () -> Unit,
    onCycleVodSeekStep: () -> Unit,
    onCycleVideoAspect: () -> Unit,
    onCycleBufferMode: () -> Unit,
    onCycleDisplayModeSwitch: () -> Unit,
    onToggleTunneling: () -> Unit,
    onCycleLiveStreamFormat: () -> Unit,
    onCycleLiveChannelSortOrder: () -> Unit,
    onCycleVodSortOrder: () -> Unit,
    onCycleEpgTimeOffset: () -> Unit,
    onToggleAutoPlayNextEpisode: () -> Unit,
    onToggleLiveVersionFailover: () -> Unit,
    onCycleSubtitleSizeScale: () -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    onToggleHomeBlock: (HomeBlock) -> Unit,
    onOrganizer: () -> Unit,
    onRefresh: () -> Unit,
    onClearLiveHistory: () -> Unit,
    onClearMovieHistory: () -> Unit,
    onClearSeriesHistory: () -> Unit,
    onClearAllHistory: () -> Unit,
    onChangePlaylist: () -> Unit,
    onParentalControl: () -> Unit,
    onAbout: () -> Unit,
    onAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmClear by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val disabled = settings.disabledHomeBlocks

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MobileIconButton(StreamiaIconGlyph.ArrowBack, onClick = onBack)
                Text("Paramètres", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Group("Compte") {
                    SettingsLink(
                        playlistName?.takeIf(String::isNotBlank) ?: "Liste active",
                        accountExpiresAtEpochSeconds?.let { "Expire le " + formatExpiry(it) } ?: "Changer de liste",
                        onChangePlaylist,
                    )
                    SettingsLink("Actualiser le catalogue", "Relire la liste depuis le serveur", onRefresh)
                    SettingsLink("Organiser", "Réordonner, masquer, déplacer, verrouiller", onOrganizer)
                }
                Group("Accueil") {
                    listOf(
                        HomeBlock.Resume to "Reprendre la lecture",
                        HomeBlock.Favorites to "Favoris",
                        HomeBlock.RecentChannels to "Dernières chaînes regardées",
                        HomeBlock.FootballScores to "Scores football",
                        HomeBlock.LiveMatches to "Matchs en direct",
                        HomeBlock.TvProgrammeNow to "Programme TV FR en direct",
                        HomeBlock.TvProgrammeTonight to "Programme TV FR ce soir",
                        HomeBlock.BeinSportsNow to "beIN Sports en direct",
                        HomeBlock.BeinSportsNext to "beIN Sports suivant",
                        HomeBlock.UkGuideNow to "UK en direct",
                        HomeBlock.UkGuideNext to "UK suivant",
                        HomeBlock.Recommendations to "Recommandations",
                    ).forEach { (block, label) ->
                        SettingsToggle(label, null, block !in disabled) { onToggleHomeBlock(block) }
                    }
                }
                Group("Lecture et direct") {
                    SettingsToggle("Aperçu en direct", "Pendant la navigation", settings.livePreviewEnabled, onToggleLivePreview)
                    SettingsValue("Délai de l'aperçu", previewDelayLabel(settings.livePreviewDelayMs), onCycleLivePreviewDelay)
                    SettingsToggle("Secours automatique", "Autre version si l'image coupe pendant 6 s", settings.liveVersionFailover, onToggleLiveVersionFailover)
                    SettingsValue("Format du flux Live", liveStreamFormatLabel(settings.liveStreamFormat), onCycleLiveStreamFormat)
                    SettingsValue("Stabilité du flux", bufferModeLabel(settings.bufferMode), onCycleBufferMode)
                    SettingsValue("Format d'image", videoAspectLabel(settings.videoAspect), onCycleVideoAspect)
                    SettingsValue("Pas d'avance / retour", "${settings.vodSeekStepSeconds} s", onCycleVodSeekStep)
                    SettingsValue("Changement de fréquence", displayModeSwitchLabel(settings.displayModeSwitch), onCycleDisplayModeSwitch)
                    SettingsToggle("Épisode suivant automatique", null, settings.autoPlayNextEpisode, onToggleAutoPlayNextEpisode)
                    SettingsToggle("Mode tunnel 4K HDR", null, settings.tunnelingEnabled, onToggleTunneling)
                }
                Group("Catalogue") {
                    SettingsValue("Tri des chaînes", liveSortLabel(settings.liveChannelSortOrder), onCycleLiveChannelSortOrder)
                    SettingsValue("Tri des films et séries", vodSortLabel(settings.vodSortOrder), onCycleVodSortOrder)
                    SettingsValue("Décalage horaire du guide", epgOffsetLabel(settings.epgTimeOffsetHours), onCycleEpgTimeOffset)
                }
                Group("Sous-titres") {
                    SettingsValue("Taille", subtitleScaleLabel(settings.subtitleSizeScale), onCycleSubtitleSizeScale)
                    SettingsToggle("Fond des sous-titres", null, settings.subtitleBackgroundEnabled, onToggleSubtitleBackground)
                }
                Group("Historique") {
                    SettingsLink("Effacer l'historique Direct", "$liveHistoryCount élément(s)") { confirmClear = "l'historique Direct" to onClearLiveHistory }
                    SettingsLink("Effacer l'historique Films", "$movieHistoryCount élément(s)") { confirmClear = "l'historique Films" to onClearMovieHistory }
                    SettingsLink("Effacer l'historique Séries", "$seriesHistoryCount élément(s)") { confirmClear = "l'historique Séries" to onClearSeriesHistory }
                    SettingsLink("Tout effacer", "Direct, Films et Séries") { confirmClear = "tous les historiques" to onClearAllHistory }
                }
                Group("Application") {
                    SettingsLink("Contrôle parental", "Code à 4 chiffres et catégories verrouillées", onParentalControl)
                    SettingsLink("Ville, assistant IA, sauvegarde, mises à jour", "Page de réglages complète (paysage)", onAdvanced)
                    SettingsLink("À propos", "Streamia v$currentVersion", onAbout)
                }
            }
        }
        confirmClear?.let { (what, action) ->
            MobileBottomSheet("Effacer $what ?", subtitle = "Les positions de lecture et chaînes récentes concernées seront supprimées.", onDismiss = { confirmClear = null }) {
                MobileSheetAction("Effacer", StreamiaIconGlyph.Delete, onClick = { confirmClear = null; action() })
                MobileSheetAction("Annuler", StreamiaIconGlyph.Close, tint = MutedInk, onClick = { confirmClear = null })
            }
        }
    }
}

@Composable
private fun Group(title: String, rows: @Composable () -> Unit) {
    Column {
        Text(
            title.uppercase(java.util.Locale.FRENCH),
            color = AccentPinkText,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.1.sp,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        )
        MobileCard(Modifier.padding(horizontal = MobileGutter).fillMaxWidth(), radius = 24.dp) {
            Column(Modifier.fillMaxWidth()) { rows() }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = MutedInk, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String?, on: Boolean, onToggle: () -> Unit) {
    SettingsRow(title, subtitle, onToggle) {
        Box(
            Modifier.width(46.dp).height(28.dp).clip(RoundedCornerShape(RadiusPill)).background(if (on) AccentPink else Color.White.copy(alpha = 0.3f)),
        ) {
            Box(
                Modifier
                    .padding(start = if (on) 21.dp else 3.dp, top = 3.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(RadiusPill))
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun SettingsValue(title: String, value: String, onCycle: () -> Unit) {
    SettingsRow(title, null, onCycle) {
        Text(value, color = MutedInk, fontSize = 13.sp)
        StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = MutedInk, size = 16.dp)
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String?, onClick: () -> Unit) {
    SettingsRow(title, subtitle, onClick) { StreamiaIcon(StreamiaIconGlyph.ArrowForward, tint = MutedInk, size = 16.dp) }
}
