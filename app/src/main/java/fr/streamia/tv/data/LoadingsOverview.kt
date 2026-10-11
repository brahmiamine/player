package fr.streamia.tv.data

/** Groupes de la page Paramètres › Chargements, dans l'ordre d'affichage. */
enum class LoadingGroup(val title: String) {
    Catalog("Catalogue"),
    Epg("Guide TV"),
    Guides("Guides tiers de l'accueil"),
    HomeBlocks("Blocs de l'accueil"),
    Scheduled("Tâches planifiées (même appli fermée)"),
    Caches("Caches sur l'appareil"),
}

enum class LoadingState(val label: String) {
    Fresh("Frais"),
    Stale("À rafraîchir"),
    Running("En cours…"),
    Scheduled("Planifiée"),
    Active("Actif"),
    Disabled("Désactivé"),
    OnDisk("Sur disque"),
}

/** Action proposée sur une ligne ; [id] de la ligne la désigne pour l'exécuter. */
enum class LoadingAction(val label: String) { Refresh("Actualiser") }

/**
 * Une source de chargement. [lastAtMillis] / [nextAtMillis] : null quand l'appli ne les connaît pas
 * (affiché « — ») ; [detail] remplace « Dernier » pour une taille sur disque.
 */
data class LoadingRow(
    val id: String,
    val group: LoadingGroup,
    val name: String,
    val subtitle: String,
    val frequency: String,
    val lastAtMillis: Long? = null,
    val nextAtMillis: Long? = null,
    val detail: String? = null,
    val state: LoadingState,
    val action: LoadingAction? = null,
)
