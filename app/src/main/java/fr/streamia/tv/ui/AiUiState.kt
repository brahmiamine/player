package fr.streamia.tv.ui

import fr.streamia.tv.data.AiSearchResult
import fr.streamia.tv.domain.MediaEntry

/**
 * État des fonctions de l'assistant IA qui ne tiennent pas à une fiche : recherche en langage naturel, « Ce soir ? »,
 * collections, « Quoi de neuf ? » et raisons des recommandations. Dans son propre flux (comme [HomeUiState]) : ses
 * mises à jour ne recomposent que les écrans qui l'affichent. Assistant coupé, tout est remis à zéro.
 */
data class AiUiState(
    val search: AiSearchUiState = AiSearchUiState(),
    val tonight: TonightUiState = TonightUiState(),
    val collections: CollectionsUiState = CollectionsUiState(),
    val brief: BriefUiState = BriefUiState(),
    /** Clé d'un contenu recommandé sur l'accueil ↦ pourquoi il est proposé (une phrase). */
    val reasons: Map<String, String> = emptyMap(),
    val remote: RemoteUiState = RemoteUiState(),
)

/** Un échange de la télécommande téléphone : ce que l'utilisateur a écrit et ce que la TV a répondu. */
data class RemoteExchange(val message: String, val reply: String)

data class RemoteUiState(val busy: Boolean = false, val log: List<RemoteExchange> = emptyList())

data class AiSearchUiState(
    /** Demande à laquelle correspondent [result] ou [error] ; un autre texte dans le champ les rend caducs. */
    val query: String = "",
    val loading: Boolean = false,
    val result: AiSearchResult? = null,
    val error: String? = null,
)

/** Une proposition de « Ce soir ? » : le contenu (ou la chaîne d'un programme TV), la raison et un détail (« TF1 · 21:10 », « Film · 7,4 »). */
data class TonightPick(val entry: MediaEntry, val why: String, val detail: String?)

data class TonightUiState(
    val loading: Boolean = false,
    val picks: List<TonightPick> = emptyList(),
    val error: String? = null,
)

data class EntryCollection(val title: String, val ordered: Boolean, val entries: List<MediaEntry>)

data class CollectionsUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val collections: List<EntryCollection> = emptyList(),
    val error: String? = null,
)

/** Un point de « Quoi de neuf ? » avec, quand la TV peut l'ouvrir, la chaîne à lancer. */
data class BriefItemUi(val text: String, val channel: MediaEntry?)

data class BriefUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val headline: String? = null,
    val items: List<BriefItemUi> = emptyList(),
    val error: String? = null,
)

/** Écrans de l'assistant (accessibles seulement quand l'IA est active). */
enum class AssistantMode(val title: String) {
    Tonight("Ce soir ?"),
    Collections("Collections"),
    WhatsNew("Quoi de neuf maintenant ?"),
    Remote("Télécommande téléphone"),
}
