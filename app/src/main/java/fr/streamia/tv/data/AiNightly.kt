package fr.streamia.tv.data

import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaType

/**
 * Pré-calcul de nuit : pendant que la TV ne sert pas, l'assistant prépare ce que la journée consultera depuis le
 * cache, sans attente ni requête. Une poignée de requêtes par jour au plus :
 *  - les collections et sagas de la semaine (une requête, même liste que l'écran) ;
 *  - la traduction des descriptions des nouveautés et des favoris (lots de huit descriptions).
 * Sans effet si l'assistant est coupé ou si le pré-calcul est désactivé dans Paramètres.
 */
internal object AiNightly {
    /** Retourne le nombre de tâches menées à bout. [shouldStop] est consulté entre deux requêtes (lecture en cours, travail annulé). */
    suspend fun run(repository: XtreamRepository, profileId: String, shouldStop: () -> Boolean): Int {
        val settings = repository.appSettings()
        val ai = repository.ai
        if (!settings.aiEnabled || !settings.aiNightly || !ai.isActive()) return 0
        val library = repository.library(profileId)
        val categories = repository.cachedCatalog(profileId)?.categories ?: return 0
        val allowed = allowedEntries(categories, library, settings.parentalControlEnabled, parentalUnlocked = false)
        var done = 0

        val pool = AiPoolBuilder(repository).collectionsPool(profileId, library, allowed)
        if (!pool.isEmpty && !shouldStop() && ai.collections(pool.candidates) != null) done++

        if (shouldStop() || !ai.isActive()) return done
        val recent = listOf(MediaType.Movie, MediaType.Series).flatMap { type ->
            runCatching { repository.homeRecommendationCandidates(profileId, type, PLOT_RECENT) }.getOrDefault(emptyList())
        }
        val favorites = runCatching { repository.entriesByKeys(profileId, library.favoriteEntries.take(PLOT_FAVORITES).toSet()) }
            .getOrDefault(emptyList())
        val entries = (favorites + recent).filter { it.type != MediaType.Live && allowed(it) }.distinctBy { it.key }
        val plots = runCatching { repository.recommendationContentFeatures(profileId, entries) }.getOrDefault(emptyMap())
            .values.mapNotNull { it.plot?.takeIf(String::isNotBlank) }
            .take(MAX_PLOTS)
        if (plots.isNotEmpty() && !shouldStop() && ai.precomputePlots(plots) > 0) done++
        return done
    }

    private const val PLOT_RECENT = 20
    private const val PLOT_FAVORITES = 12
    private const val MAX_PLOTS = 40
}
