package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.liveonsat.LiveOnSatMatch
import fr.streamia.tv.liveonsat.LiveOnSatParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class LiveOnSatFetchResult(
    val matches: List<LiveOnSatMatch>,
    val fetchedAtEpochMillis: Long,
    /**
     * `true` quand [matches] vient du cache disque plutôt que d'un scrape qui vient de réussir —
     * soit parce que le cache était encore assez récent (aucun scrape tenté), soit parce qu'un
     * scrape a été tenté et a échoué, auquel cas ces données peuvent dater d'un cycle précédent.
     */
    val fromCache: Boolean,
)

/**
 * Orchestre scrape + cache pour liveonsat.com. Volontairement séparé de [XtreamRepository] : ces
 * données ne dépendent d'aucun profil Xtream/M3U, seule leur mise en correspondance avec les
 * chaînes (faite par l'appelant via [fr.streamia.tv.liveonsat.ChannelMatcher]) l'est.
 */
internal class LiveOnSatRepository(context: Context) {
    private val client = LiveOnSatClient()
    private val cache = LiveOnSatCache(context)

    /**
     * Réutilise le cache tant qu'il est plus récent que [maxAgeMillis], sauf si [forceRefresh].
     * Un nouveau scrape qui échoue retombe sur le cache existant plutôt que de faire échouer
     * l'écran — l'exception n'est propagée que si aucune donnée n'est disponible du tout.
     */
    suspend fun loadMatches(forceRefresh: Boolean, maxAgeMillis: Long): LiveOnSatFetchResult =
        withContext(Dispatchers.IO) {
            val cached = cache.load()
            val isFresh = cached != null && System.currentTimeMillis() - cached.fetchedAtEpochMillis < maxAgeMillis
            if (!forceRefresh && isFresh && cached != null) {
                return@withContext LiveOnSatFetchResult(cached.matches, cached.fetchedAtEpochMillis, fromCache = true)
            }

            val refreshed = runCatching { LiveOnSatParser.parse(client.fetchTodayHtml()) }
            refreshed.getOrNull()?.let { matches ->
                val fetchedAt = System.currentTimeMillis()
                cache.save(matches, fetchedAt)
                return@withContext LiveOnSatFetchResult(matches, fetchedAt, fromCache = false)
            }

            if (cached != null) {
                return@withContext LiveOnSatFetchResult(cached.matches, cached.fetchedAtEpochMillis, fromCache = true)
            }
            throw refreshed.exceptionOrNull() ?: IOException("Impossible de récupérer les matchs du jour.")
        }
}
