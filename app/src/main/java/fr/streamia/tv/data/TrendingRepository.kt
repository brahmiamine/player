package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.recommendation.MetadataSimilarityEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rangées JustWatch (films et séries tendance) rapprochées des titres de la playlist. Les titres
 * JustWatch sont communs à toutes les listes (mémoire, puis disque, puis réseau) ; le
 * rapprochement de chaque liste est gardé sur disque pour un affichage immédiat.
 */
class TrendingRepository internal constructor(
    private val appContext: Context,
    private val cache: CatalogCache,
) {
    private val justWatch = JustWatchClient()
    private val justWatchCache = ConcurrentHashMap<JustWatchSection, Pair<Long, List<TrendingTitle>>>()

    /**
     * Dernier rapprochement JustWatch ↔ playlist de cette rangée, gardé sur disque : l'accueil
     * l'affiche dès l'ouverture, sans réseau ni recherche. [CachedJustWatchRow.fresh] faux au-delà
     * de [JUSTWATCH_TTL_MS] : à recalculer (l'ancienne rangée reste affichée en attendant).
     */
    suspend fun cachedJustWatch(profileId: String, section: JustWatchSection): CachedJustWatchRow? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(justWatchFile(profileId, section).readText())
            val keys = root.getJSONArray("keys").let { array -> (0 until array.length()).map(array::getString) }
            val byKey = cache.loadEntriesByKeys(profileId, keys.toSet()).associateBy(MediaEntry::key)
            CachedJustWatchRow(keys.mapNotNull(byKey::get), fresh = System.currentTimeMillis() - root.getLong("at") < JUSTWATCH_TTL_MS)
        }.getOrNull()
    }

    private fun justWatchTitlesFile(section: JustWatchSection) = File(appContext.filesDir, "justwatch-titles-${section.name}.json")

    /** Titres encore frais sur disque (horodatage d'origine gardé), sinon null. */
    private fun loadJustWatchTitles(section: JustWatchSection, now: Long): Pair<Long, List<TrendingTitle>>? = runCatching {
        val root = JSONObject(justWatchTitlesFile(section).readText())
        val at = root.getLong("at").takeIf { now - it < JUSTWATCH_TTL_MS } ?: return@runCatching null
        val array = root.getJSONArray("titles")
        at to (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            TrendingTitle(
                type = section.type,
                title = item.getString("title"),
                originalTitle = item.optString("originalTitle").ifBlank { null },
                year = item.optInt("year").takeIf { it > 0 },
            )
        }
    }.getOrNull()

    private fun fetchJustWatchTitles(section: JustWatchSection, now: Long): Pair<Long, List<TrendingTitle>> {
        val titles = justWatch.titles(section)
        runCatching {
            val array = JSONArray(titles.map { JSONObject().put("title", it.title).put("originalTitle", it.originalTitle).put("year", it.year) })
            justWatchTitlesFile(section).writeText(JSONObject().put("at", now).put("titles", array).toString())
        }
        return now to titles
    }

    private fun justWatchFile(profileId: String, section: JustWatchSection) =
        File(appContext.filesDir, "justwatch-$profileId-${section.name}.json")

    /** Section JustWatch (cache mémoire 6 h) réduite aux titres présents dans le catalogue, ordre JustWatch. */
    suspend fun justWatch(profileId: String, section: JustWatchSection, limit: Int): List<MediaEntry> {
        val now = System.currentTimeMillis()
        // Titres JustWatch communs à toutes les listes : mémoire, puis disque, puis réseau.
        val titles = justWatchCache[section]?.takeIf { now - it.first < JUSTWATCH_TTL_MS }?.second
            ?: withContext(Dispatchers.IO) { loadJustWatchTitles(section, now) ?: fetchJustWatchTitles(section, now) }
                .also { justWatchCache[section] = it }.second
        val result = LinkedHashMap<String, MediaEntry>()
        for (title in titles) {
            if (result.size >= limit) break
            val match = cache.search(profileId, title.title, title.type, JUSTWATCH_SEARCH_LIMIT).firstOrNull { matchesTrending(it, title) }
                ?: title.originalTitle?.let { original ->
                    cache.search(profileId, original, title.type, JUSTWATCH_SEARCH_LIMIT).firstOrNull { matchesTrending(it, title) }
                }
            match?.let { result.putIfAbsent(titleKey(it.displayName), it) }
        }
        withContext(Dispatchers.IO) {
            runCatching {
                justWatchFile(profileId, section).writeText(
                    JSONObject().put("at", now).put("keys", JSONArray(result.values.map(MediaEntry::key))).toString(),
                )
            }
        }
        return result.values.toList()
    }
}

private const val JUSTWATCH_TTL_MS = 6 * 60 * 60 * 1000L
private const val JUSTWATCH_SEARCH_LIMIT = 30
private val titleTokenizer = MetadataSimilarityEngine()
private val YEAR_IN_NAME = Regex("\\b(19|20)\\d{2}\\b")

/** Titre normalisé sans année, qualité ni préfixe de langue : « FR - Dune (2021) 4K » → « dune ». */
internal fun titleKey(name: String): String = titleTokenizer.titleTokens(name).joinToString(" ")

/** Même titre (français ou original) et, si le nom IPTV porte une année, à un an près. */
internal fun matchesTrending(entry: MediaEntry, title: TrendingTitle): Boolean {
    val key = titleKey(entry.displayName)
    if (key.isEmpty() || (key != titleKey(title.title) && key != title.originalTitle?.let(::titleKey))) return false
    val year = YEAR_IN_NAME.findAll(entry.displayName).lastOrNull()?.value?.toInt() ?: return true
    return title.year == null || kotlin.math.abs(year - title.year) <= 1
}
