package fr.streamia.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.ui.AiButtonLabel
import fr.streamia.tv.ui.AiLoadingIndicator
import fr.streamia.tv.ui.AiSearchUiState
import fr.streamia.tv.ui.ChannelLogo
import fr.streamia.tv.ui.StreamiaIcon
import fr.streamia.tv.ui.StreamiaIconGlyph
import fr.streamia.tv.ui.TvTextField
import fr.streamia.tv.ui.theme.AccentPink
import fr.streamia.tv.ui.theme.AccentPinkText
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.delay

/**
 * Recherche globale au doigt : champ en haut, filtres Tout/Direct/Films/Séries, résultats en
 * lignes. Appui long sur un résultat : favori. La saisie interroge la base du catalogue après
 * 220 ms sans frappe, comme sur la TV.
 */
@Composable
fun MobileSearchScreen(
    favoriteEntries: Set<String>,
    query: String,
    type: MediaType?,
    /** Résultat ouvert avant le retour : la liste défile jusqu'à lui et le met en évidence. */
    restoreEntryKey: String?,
    onRestoreConsumed: () -> Unit,
    search: suspend (String, MediaType?) -> List<MediaEntry>,
    onQueryChange: (String) -> Unit,
    onTypeChange: (MediaType?) -> Unit,
    onOpenEntry: (MediaEntry) -> Unit,
    onToggleEntryFavorite: (MediaEntry) -> Unit,
    onBack: () -> Unit,
    /** Assistant actif : recherche en phrase (au clavier ou à la voix), résultats vérifiés dans la playlist. */
    aiActive: Boolean = false,
    aiSearch: AiSearchUiState = AiSearchUiState(),
    onAiSearch: (String, MediaType?) -> Unit = { _, _ -> },
    onClearAiSearch: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val needle = query.trim().lowercase()
    val aiForQuery = aiSearch.takeIf { aiActive && it.query == query.trim() && it.type == type }
    val aiEntries = aiForQuery?.result?.entries.orEmpty()
    val showAi = aiEntries.isNotEmpty()
    // Voix : le micro du clavier de reconnaissance du téléphone remplit le champ, puis l'assistant comprend la phrase.
    val context = androidx.compose.ui.platform.LocalContext.current
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (spoken.isNotEmpty()) {
            onQueryChange(spoken)
            onAiSearch(spoken, type)
        }
    }
    val canSpeak = remember {
        context.packageManager.queryIntentActivities(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    var entries by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var searching by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<MediaEntry?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Clé gardée localement : le contexte de retour est consommé dès la liste replacée, le liseré doit rester.
    val targetKey = remember { restoreEntryKey }
    var restored by remember { mutableStateOf(false) }
    // Les résultats sont relancés au retour : une fois la liste revenue, on se replace sur l'élément ouvert (après l'en-tête).
    val shown = if (showAi) aiEntries else entries
    LaunchedEffect(shown) {
        if (targetKey == null || restored) return@LaunchedEffect
        val index = shown.indexOfFirst { it.key == targetKey }
        if (index >= 0) {
            listState.scrollToItem(index + 1)
            restored = true
            onRestoreConsumed()
        }
    }
    LaunchedEffect(needle, type) {
        if (needle.isBlank()) {
            entries = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(220)
        entries = search(needle, type)
        searching = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MobileIconButton(StreamiaIconGlyph.ArrowBack, onClick = onBack)
                TvTextField(query, onQueryChange, if (aiActive) "Chaîne, film, série… ou une phrase" else "Chaîne, film ou série", Modifier.weight(1f))
                if (aiActive && canSpeak) {
                    MobileIconButton(StreamiaIconGlyph.Mic, onClick = {
                        speech.launch(
                            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Que voulez-vous regarder ?"),
                        )
                    })
                }
            }
            if (aiActive && needle.length >= MIN_AI_QUERY_CHARS) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = MobileGutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(AccentPink).clickable(enabled = aiForQuery?.loading != true) { onAiSearch(query.trim(), type) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) { AiButtonLabel("Chercher avec l'IA", aiForQuery?.loading == true) }
                    AiLoadingIndicator("L'assistant comprend…", aiForQuery?.loading == true)
                }
                aiForQuery?.result?.plan?.summary?.takeIf(String::isNotBlank)?.let { summary ->
                    Text("✦ $summary", color = AccentPinkText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = MobileGutter, vertical = 4.dp))
                }
                aiForQuery?.error?.let { Text(it, color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(horizontal = MobileGutter, vertical = 4.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            MobileChipRow {
                MobileChip("Tout", type == null, onClick = { onTypeChange(null) })
                MediaType.entries.forEach { mediaType ->
                    MobileChip(mediaType.displayName, type == mediaType, onClick = { onTypeChange(mediaType) })
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                needle.isBlank() -> MobileEmptyState("Tapez quelques lettres pour rechercher dans tout le catalogue.")
                shown.isEmpty() && (searching || aiForQuery?.loading == true) -> MobileEmptyState("Recherche…")
                shown.isEmpty() -> MobileEmptyState("Aucun résultat pour « $query ».")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = MobileGutter, end = MobileGutter, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            if (showAi) "Sélection de l'assistant · ${shown.size}" else if (searching) "Recherche…" else if (shown.size == 1) "1 résultat" else "${shown.size} résultats",
                            color = MutedInk,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    items(shown, key = { it.key }) { entry ->
                        MobileCard(
                            Modifier.fillMaxWidth().then(
                                if (entry.key == targetKey) Modifier.border(2.dp, fr.streamia.tv.ui.theme.AccentPink, RoundedCornerShape(fr.streamia.tv.ui.theme.RadiusTile)) else Modifier,
                            ),
                            onClick = { onOpenEntry(entry) }, onLongClick = { actions = entry },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ChannelLogo(entry.iconUrl, entry.displayName, Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)), imagePadding = 4)
                                Column(Modifier.weight(1f)) {
                                    Text(entry.displayName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (entry.type == MediaType.Live) "Chaîne ${entry.number}" else entry.rating?.takeIf { it > 0.0 }?.let { "★ %.1f".format(java.util.Locale.FRENCH, it) } ?: entry.type.displayName,
                                        color = MutedInk,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                    )
                                }
                                if (entry.key in favoriteEntries) StreamiaIcon(StreamiaIconGlyph.Star, size = 16.dp)
                                Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                                    Text(
                                        when (entry.type) {
                                            MediaType.Live -> "Direct"
                                            MediaType.Movie -> "Film"
                                            MediaType.Series -> "Série"
                                        },
                                        color = Ink,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        actions?.let { entry ->
            val favorite = entry.key in favoriteEntries
            MobileBottomSheet(entry.displayName, onDismiss = { actions = null }) {
                MobileSheetAction("Ouvrir", StreamiaIconGlyph.Movie, onClick = { actions = null; onOpenEntry(entry) })
                MobileSheetAction(
                    if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                    if (favorite) StreamiaIconGlyph.StarOutline else StreamiaIconGlyph.Star,
                    onClick = { actions = null; onToggleEntryFavorite(entry) },
                )
            }
        }
    }
}

/** En dessous, la demande est trop courte pour être une phrase à comprendre. */
private const val MIN_AI_QUERY_CHARS = 4
