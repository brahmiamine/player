package fr.streamia.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.FocusBlueBright
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Scores du jour via le JSON de la page BBC Sport « Scores & Fixtures » (usage personnel) :
// ~35 compétitions, en direct. La BBC ne fournit aucun logo : écussons de clubs via TheSportsDB
// (gratuit, mis en cache), logos de compétitions via les logos ESPN des grandes ligues.
// ponytail: API non documentée, peut changer sans préavis — la rangée disparaît alors simplement.
private const val BBC_SCORES_URL = "https://web-cdn.api.bbci.co.uk/wc-poll-data/container/sport-data-scores-fixtures" +
    "?urn=urn%3Abbc%3Asportsdata%3Afootball%3Atournament-collection%3Acollated"
private val COMPETITION_LOGOS = mapOf(
    "Premier League" to 23, "Championship" to 24, "Scottish Premiership" to 45, "FA Cup" to 40,
    "Spanish La Liga" to 15, "Italian Serie A" to 12, "German Bundesliga" to 10, "French Ligue 1" to 9,
    "Portuguese Primeira Liga" to 14, "Dutch Eredivisie" to 11, "Belgian Pro League" to 6, "Major League Soccer" to 19,
    "Champions League" to 2, "Europa League" to 2310, "Europa Conference League" to 20296, "Conference League" to 20296,
    "UEFA Nations League" to 2395, "World Cup" to 4, "European Championship" to 74,
).mapValues { "https://a.espncdn.com/i/leaguelogos/soccer/500/${it.value}.png" }
private const val FOOTBALL_LIVE_REFRESH_MS = 60_000L
private const val FOOTBALL_IDLE_REFRESH_MS = 15 * 60_000L
private const val FOOTBALL_KICKOFF_SOON_MS = 15 * 60_000L

internal data class FootballMatch(
    val id: String,
    val competition: String,
    val competitionLogo: String?,
    val home: String,
    val homeLogo: String?,
    val homeScore: String,
    val away: String,
    val awayLogo: String?,
    val awayScore: String,
    val state: String, // "in" (en direct), "pre" (à venir), "post" (terminé)
    val status: String,
    val kickoff: Instant,
)

/** En direct, puis terminés (plus récent en premier), puis à venir (plus proche en premier). */
internal fun sortFootballMatches(matches: List<FootballMatch>): List<FootballMatch> =
    matches.sortedWith(
        compareBy<FootballMatch> { when (it.state) { "in" -> 0; "post" -> 1; else -> 2 } }
            .thenBy { if (it.state == "post") -it.kickoff.epochSecond else it.kickoff.epochSecond },
    )

private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 8_000
    connection.readTimeout = 8_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    return try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun bbcState(status: String): String? = when (status) {
    "PreEvent" -> "pre"
    "PostEvent" -> "post"
    "MidEvent" -> "in"
    else -> null // reporté, annulé, arrêté… : ignoré
}

private fun fetchBbcMatches(day: String): List<FootballMatch> {
    val root = JSONObject(httpGet("$BBC_SCORES_URL&selectedStartDate=$day&selectedEndDate=$day&todayDate=$day"))
    val groups = root.optJSONArray("eventGroups") ?: return emptyList()
    val matches = mutableListOf<FootballMatch>()
    for (g in 0 until groups.length()) {
        val group = groups.getJSONObject(g)
        val competition = group.optString("displayLabel")
        val logo = COMPETITION_LOGOS.entries.firstOrNull { competition.contains(it.key, ignoreCase = true) }?.value
        val secondary = group.optJSONArray("secondaryGroups") ?: continue
        for (sg in 0 until secondary.length()) {
            val events = secondary.getJSONObject(sg).optJSONArray("events") ?: continue
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val state = bbcState(event.optString("status")) ?: continue
                val home = event.getJSONObject("home")
                val away = event.getJSONObject("away")
                matches += FootballMatch(
                    id = event.getString("id"),
                    competition = competition,
                    competitionLogo = logo,
                    home = home.optString("shortName"),
                    homeLogo = null,
                    homeScore = home.optString("score"),
                    away = away.optString("shortName"),
                    awayLogo = null,
                    awayScore = away.optString("score"),
                    state = state,
                    status = event.optJSONObject("periodLabel")?.optString("value").orEmpty(),
                    kickoff = runCatching { Instant.parse(event.getString("startDateTime")) }.getOrDefault(Instant.EPOCH),
                )
            }
        }
    }
    return matches
}

/** Chaque minute si un match est en cours ou commence bientôt, sinon toutes les 15 min. */
internal fun footballRefreshDelayMs(matches: List<FootballMatch>, now: Instant): Long =
    if (matches.any { it.state == "in" || (it.state == "pre" && it.kickoff.toEpochMilli() - now.toEpochMilli() < FOOTBALL_KICKOFF_SOON_MS) }) {
        FOOTBALL_LIVE_REFRESH_MS
    } else {
        FOOTBALL_IDLE_REFRESH_MS
    }

/** Écussons TheSportsDB par nom d'équipe, gardés pour toute la session ("" = introuvable). */
private val badgeCache = java.util.concurrent.ConcurrentHashMap<String, String>()
// ponytail: clé publique « 3 » limitée à ~30 requêtes/min — on plafonne les recherches par rafraîchissement,
// les écussons manquants arrivent aux rafraîchissements suivants.
private const val MAX_BADGE_LOOKUPS_PER_REFRESH = 20

private fun lookupBadge(team: String): String? {
    badgeCache[team]?.let { return it.ifBlank { null } }
    val teams = JSONObject(httpGet("https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=" + java.net.URLEncoder.encode(team, "UTF-8")))
        .optJSONArray("teams")
    val badge = (0 until (teams?.length() ?: 0)).map { teams!!.getJSONObject(it) }
        .firstOrNull { it.optString("strSport") == "Soccer" }
        ?.optString("strBadge").orEmpty()
    badgeCache[team] = badge
    return badge.ifBlank { null }
}

private fun withBadges(matches: List<FootballMatch>): List<FootballMatch> {
    var budget = MAX_BADGE_LOOKUPS_PER_REFRESH
    fun badge(team: String): String? =
        if (badgeCache.containsKey(team) || budget-- > 0) runCatching { lookupBadge(team) }.getOrNull() else null
    return matches.map { it.copy(homeLogo = badge(it.home), awayLogo = badge(it.away)) }
}

// Matches du jour uniquement (en direct, terminés, à venir).
private suspend fun fetchTodayMatches(): List<FootballMatch> = withContext(Dispatchers.IO) {
    fetchBbcMatches(LocalDate.now(ZoneId.systemDefault()).toString())
        .distinctBy { it.id }
        .let(::sortFootballMatches)
        .let(::withBadges)
}

/**
 * Dernier chargement (horodatage, matchs), partagé entre les recompositions : la rangée est détruite
 * quand elle sort de l'écran, et son retour ne doit pas relancer une requête BBC si les données sont
 * encore valables (1 min pendant un match, 15 min sinon).
 */
@Volatile private var footballCache: Pair<Long, List<FootballMatch>>? = null

@Composable
internal fun FootballScoresRow(modifier: Modifier = Modifier) {
    var matches by remember { mutableStateOf(footballCache?.second.orEmpty()) }
    // Squelette seulement avant le tout premier chargement de la session (cache mémoire vide).
    var firstLoadDone by remember { mutableStateOf(footballCache != null) }
    // Seulement app visible : aucune requête en arrière-plan, rafraîchissement immédiat au retour.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                // Un échec compte aussi comme un chargement (données précédentes gardées) : pas de
                // nouvel essai avant le prochain créneau, au lieu d'insister en boucle.
                val loaded = footballCache
                    ?.takeIf { (at, cached) -> now - at < footballRefreshDelayMs(cached, Instant.ofEpochMilli(now)) }
                    ?: (now to (runCatching { fetchTodayMatches() }.getOrNull() ?: footballCache?.second.orEmpty()))
                        .also { footballCache = it }
                matches = loaded.second
                firstLoadDone = true
                delay(loaded.first + footballRefreshDelayMs(loaded.second, Instant.ofEpochMilli(now)) - now)
            }
        }
    }
    if (!firstLoadDone) {
        SkeletonRow("Scores football", modifier) { FootballCardSkeleton() }
        return
    }
    if (matches.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Scores football", fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(matches, key = FootballMatch::id) { FootballMatchCard(it) }
        }
    }
}

private val kickoffFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
@Composable
private fun FootballMatchCard(match: FootballMatch) {
    FocusableSurface(onClick = {}, modifier = Modifier.width(260.dp).height(150.dp)) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(match.competitionLogo, match.competition, Modifier.size(22.dp), imagePadding = 1)
                Spacer(Modifier.width(6.dp))
                Text(match.competition, color = MutedInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (match.state == "in") LiveBadge()
            }
            Spacer(Modifier.height(8.dp))
            TeamLine(match.home, match.homeLogo, match.homeScore.takeIf { match.state != "pre" })
            Spacer(Modifier.height(6.dp))
            TeamLine(match.away, match.awayLogo, match.awayScore.takeIf { match.state != "pre" })
            Spacer(Modifier.weight(1f))
            Text(
                when (match.state) {
                    "pre" -> kickoffFormat.format(match.kickoff)
                    "post" -> "Terminé"
                    else -> match.status
                },
                color = if (match.state == "in") FocusBlueBright else MutedInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TeamLine(name: String, logo: String?, score: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelLogo(logo, name, Modifier.size(28.dp), imagePadding = 1)
        Spacer(Modifier.width(8.dp))
        Text(name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (score != null) Text(score, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
