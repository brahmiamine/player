package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch

/**
 * Guides tiers de l'accueil : matchs du jour (liveonsat.com), programmes FR en direct et du soir
 * (tv-programme.com), grille beIN SPORTS MENA et grille britannique. Chaque source garde un cache
 * disque ; seules la résolution des matchs et sa version dépendent d'un profil.
 */
class HomeGuidesRepository internal constructor(
    context: Context,
    private val cache: CatalogCache,
    private val playlistStore: PlaylistStore,
    private val epgCache: EpgCache,
) {
    private val liveOnSatRepository = LiveOnSatRepository(context)
    private val tvProgrammeRepository = TvProgrammeRepository(context)
    private val tvProgrammeNowRepository = TvProgrammeNowRepository(context)
    private val beinSportsGuideRepository = BeinSportsGuideRepository(context)
    private val ukGuideRepository = UkGuideRepository(context)

    /**
     * Matchs du jour scrapés depuis liveonsat.com, sans lien avec un profil Xtream/M3U particulier
     * (seule leur mise en correspondance avec les chaînes, faite par l'appelant, en dépend).
     */
    suspend fun loadLiveOnSatMatches(forceRefresh: Boolean = false): LiveOnSatFetchResult =
        liveOnSatRepository.loadMatches(forceRefresh, maxAgeMillis = LIVE_ONSAT_CACHE_MAX_AGE_MS)

    /**
     * Les trois sources du rapprochement liveonsat : scrape, actualisation de la playlist (chaînes)
     * et synchronisation EPG (horaires). À calculer avant le rapprochement et à réutiliser pour
     * l'enregistrer, pour qu'une synchronisation survenue pendant le calcul le fasse refaire.
     */
    suspend fun liveOnSatResolutionVersion(profileId: String, fetch: LiveOnSatFetchResult): String {
        val catalogRefreshedAt = playlistStore.find(profileId)?.lastRefreshAt ?: 0L
        val epgSyncedAt = epgCache.metadata(profileId)?.syncedAtMillis ?: 0L
        return "${fetch.fetchedAtEpochMillis}|$catalogRefreshedAt|$epgSyncedAt"
    }

    /** Rapprochement chaînes/EPG déjà calculé pour cette [version], ou null s'il faut le refaire. */
    suspend fun cachedLiveOnSatResolution(profileId: String, version: String, fetch: LiveOnSatFetchResult): List<ResolvedLiveOnSatMatch>? {
        val saved = liveOnSatRepository.loadResolution(profileId, version)
            ?.takeIf { it.size == fetch.matches.size }
            ?: return null
        val keys = saved.flatMapTo(mutableSetOf()) { it.channelKeys.values.flatten() }
        val entries = cache.loadEntriesByKeys(profileId, keys).associateBy(MediaEntry::key)
        return fetch.matches.zip(saved) { match, resolution ->
            ResolvedLiveOnSatMatch(
                match = match,
                matchedChannels = resolution.channelKeys
                    .mapValues { (_, channelKeys) -> channelKeys.mapNotNull(entries::get) }
                    .filterValues { it.isNotEmpty() },
                epgStartEpochSeconds = resolution.epgStartEpochSeconds,
                epgEndEpochSeconds = resolution.epgEndEpochSeconds,
            )
        }
    }

    suspend fun saveLiveOnSatResolution(profileId: String, version: String, resolved: List<ResolvedLiveOnSatMatch>) =
        liveOnSatRepository.saveResolution(
            profileId,
            version,
            resolved.map {
                LiveOnSatResolution(
                    channelKeys = it.matchedChannels.mapValues { (_, channels) -> channels.map(MediaEntry::key) },
                    epgStartEpochSeconds = it.epgStartEpochSeconds,
                    epgEndEpochSeconds = it.epgEndEpochSeconds,
                )
            },
        )

    /** Cache disque encore frais : les chargements correspondants ne contacteront aucun site. */
    suspend fun hasFreshLiveOnSatCache() = liveOnSatRepository.hasFreshCache(LIVE_ONSAT_CACHE_MAX_AGE_MS)
    suspend fun hasFreshTvProgrammeTonightCache() = tvProgrammeRepository.hasFreshCache(TV_PROGRAMME_CACHE_MAX_AGE_MS)
    suspend fun hasFreshTvProgrammeNowCache() = tvProgrammeNowRepository.hasFreshCache(TV_PROGRAMME_NOW_CACHE_MAX_AGE_MS)
    suspend fun hasFreshBeinSportsGuideCache() = beinSportsGuideRepository.hasFreshCache(BEIN_SPORTS_GUIDE_CACHE_MAX_AGE_MS)
    suspend fun hasFreshUkGuideCache() = ukGuideRepository.hasFreshCache(UK_GUIDE_CACHE_MAX_AGE_MS)

    // Données déjà sur disque, quel que soit leur âge : affichées immédiatement pendant que le
    // chargement habituel (load*) les actualise, au lieu d'un squelette le temps du téléchargement.
    suspend fun cachedLiveOnSatMatches() = liveOnSatRepository.cached()
    suspend fun cachedTvProgrammeTonight() = tvProgrammeRepository.cached()
    suspend fun cachedTvProgrammeNow() = tvProgrammeNowRepository.cached()
    suspend fun cachedBeinSportsGuide() = beinSportsGuideRepository.cached()
    suspend fun cachedUkGuide() = ukGuideRepository.cached()

    /** Réseau revenu : les guides en attente après un échec peuvent réessayer tout de suite. */
    fun clearGuideFailureBackoffs() = tvProgrammeNowRepository.clearFailureBackoff()

    /** Programmes TV français du soir scrapés depuis tv-programme.com avec cache local. */
    suspend fun loadTvProgrammeTonight(forceRefresh: Boolean = false): TvProgrammeFetchResult =
        tvProgrammeRepository.loadTonight(forceRefresh, maxAgeMillis = TV_PROGRAMME_CACHE_MAX_AGE_MS)

    /** Programmes TV français actuellement diffusés, rafraîchis fréquemment. */
    suspend fun loadTvProgrammeNow(forceRefresh: Boolean = false): TvProgrammeNowFetchResult =
        tvProgrammeNowRepository.loadNow(forceRefresh, maxAgeMillis = TV_PROGRAMME_NOW_CACHE_MAX_AGE_MS)

    /** Grille MENA beIN SPORTS : programmes en cours et suivants avec cache court. */
    suspend fun loadBeinSportsGuide(forceRefresh: Boolean = false): BeinSportsGuideFetchResult =
        beinSportsGuideRepository.loadGuide(forceRefresh, maxAgeMillis = BEIN_SPORTS_GUIDE_CACHE_MAX_AGE_MS)

    /** Grille TV britannique (tvguideuk.com) : programmes en cours et suivants avec cache court. */
    suspend fun loadUkGuide(forceRefresh: Boolean = false): UkGuideFetchResult =
        ukGuideRepository.loadGuide(forceRefresh, maxAgeMillis = UK_GUIDE_CACHE_MAX_AGE_MS)

    /** Liste supprimée : son rapprochement des matchs n'a plus de raison d'être. */
    suspend fun clearLiveOnSatResolution(profileId: String) = liveOnSatRepository.clearResolution(profileId)

    companion object {
        // Chargé au démarrage de l'app puis relu depuis ce cache disque (page Matchs, accueil) :
        // un nouveau scrape au plus toutes les 2 h, ou sur le bouton Actualiser. liveonsat.com n'a
        // pas d'API et ne doit pas être sollicité plus souvent que nécessaire.
        const val LIVE_ONSAT_CACHE_MAX_AGE_MS = 2 * 60 * 60_000L

        // Le programme du soir change beaucoup moins souvent que les matchs live. Deux heures
        // limitent les requêtes vers tv-programme.com tout en renouvelant les données dans la soirée.
        private const val TV_PROGRAMME_CACHE_MAX_AGE_MS = 2 * 60 * 60_000L

        // Le cache garde toute la grille (programme en cours recalculé localement) : pas besoin de
        // re-scraper souvent, et tv-programme.com bloque (403) les requêtes trop fréquentes.
        private const val TV_PROGRAMME_NOW_CACHE_MAX_AGE_MS = 30 * 60_000L

        // La grille couvre 24 h et "maintenant"/"suivant" est recalculé localement depuis le cache
        // (boucle de 2 min de l'accueil) : re-télécharger plus souvent ne changerait rien.
        private const val BEIN_SPORTS_GUIDE_CACHE_MAX_AGE_MS = 30 * 60_000L

        // Grille complète en cache, créneau courant recalculé localement : ~16 requêtes par
        // téléchargement, donc pas plus d'une fois par demi-heure vers tvguideuk.com.
        private const val UK_GUIDE_CACHE_MAX_AGE_MS = 30 * 60_000L
    }
}
