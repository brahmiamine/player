package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaType

/** Étapes du premier chargement du catalogue, dans l'ordre où elles s'exécutent. */
enum class CatalogStep(val label: String, val unit: String) {
    Live("Chaînes du Direct", "chaînes"),
    Movies("Films", "films"),
    Series("Séries", "séries"),
    M3uFallback("Secours M3U", ""),
    Finalize("Index de recherche et compteurs", ""),
}

enum class CatalogStepStatus { Waiting, Running, Done, Skipped }

/** Reçoit l'avancement depuis le thread de chargement (IO) ; l'implémentation doit être rapide. */
fun interface CatalogProgressListener {
    fun onStep(step: CatalogStep, status: CatalogStepStatus, count: Int)
}

/**
 * Avancement affiché par la notification de l'accueil. [failure] non nul = chargement échoué
 * (la notification reste, « Actualiser » relance) ; [finished] = tout est écrit et relu.
 */
data class CatalogLoadProgress(
    val statuses: Map<CatalogStep, CatalogStepStatus> = CatalogStep.entries.associateWith { CatalogStepStatus.Waiting },
    val counts: Map<CatalogStep, Int> = emptyMap(),
    val finished: Boolean = false,
    val failure: String? = null,
    /** Sections dont la tuile d'accueil affiche encore un chargeur (vide si un catalogue déjà affiché sert en attendant). */
    val pendingSections: Set<MediaType> = emptySet(),
) {
    /** Les tuiles Direct / Films / Séries affichent leur chargeur tant que c'est vrai. */
    val loading: Boolean get() = !finished && failure == null

    fun status(step: CatalogStep): CatalogStepStatus = statuses[step] ?: CatalogStepStatus.Waiting

    fun with(step: CatalogStep, status: CatalogStepStatus, count: Int): CatalogLoadProgress =
        copy(statuses = statuses + (step to status), counts = if (count > 0) counts + (step to count) else counts)

    /** Étapes effectives (le secours M3U n'en est une que s'il a servi). */
    val doneCount: Int get() = CatalogStep.entries.count { status(it) == CatalogStepStatus.Done }

    val totalCount: Int get() = CatalogStep.entries.count { status(it) != CatalogStepStatus.Skipped }

    val current: CatalogStep? get() = CatalogStep.entries.firstOrNull { status(it) == CatalogStepStatus.Running }
}
