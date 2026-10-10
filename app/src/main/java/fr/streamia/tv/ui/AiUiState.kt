package fr.streamia.tv.ui

import fr.streamia.tv.data.AiSearchResult
import fr.streamia.tv.data.TonightAnswers
import fr.streamia.tv.data.TonightCompany
import fr.streamia.tv.data.TonightLength
import fr.streamia.tv.data.TonightMood
import fr.streamia.tv.domain.MediaEntry

/**
 * État des fonctions de l'assistant IA qui ne tiennent pas à une fiche : recherche en langage naturel, « Ce soir ? »,
 * « Quoi de neuf ? » et raisons des recommandations. Dans son propre flux (comme [HomeUiState]) : ses
 * mises à jour ne recomposent que les écrans qui l'affichent. Assistant coupé, tout est remis à zéro.
 */
data class AiUiState(
    val search: AiSearchUiState = AiSearchUiState(),
    val tonight: TonightUiState = TonightUiState(),
    val brief: BriefUiState = BriefUiState(),
    /** Clé d'un contenu recommandé sur l'accueil ↦ pourquoi il est proposé (une phrase). */
    val reasons: Map<String, String> = emptyMap(),
    val remote: RemoteUiState = RemoteUiState(),
)

/** Un échange de la télécommande téléphone : ce que l'utilisateur a écrit et ce que la TV a répondu. */
data class RemoteExchange(val message: String, val reply: String)

/** [url] : adresse de la page de chat servie sur le réseau local (null tant que le serveur n'est pas prêt ou sans réseau local). */
data class RemoteUiState(val busy: Boolean = false, val log: List<RemoteExchange> = emptyList(), val url: String? = null)

data class AiSearchUiState(
    /** Demande à laquelle correspondent [result] ou [error] ; un autre texte dans le champ les rend caducs. */
    val query: String = "",
    /** Filtre Direct/Films/Séries sous lequel la recherche a été faite : en changer rend le résultat caduc. */
    val type: fr.streamia.tv.domain.MediaType? = null,
    val loading: Boolean = false,
    val result: AiSearchResult? = null,
    val error: String? = null,
)

/** Une proposition de « Ce soir ? » : le contenu (ou la chaîne d'un programme TV), la raison et un détail (« TF1 · 21:10 », « Film · 7,4 »). */
data class TonightPick(val entry: MediaEntry, val why: String, val detail: String?)

/** Réponses par défaut de « Ce soir ? », avant toute demande : Détente · Un film · Seul. */
val DefaultTonightAnswers = TonightAnswers(TonightMood.Relax, TonightLength.Film, TonightCompany.Alone)

/** Résumé des réponses, tel que l'accueil et la pastille « Modifier » l'affichent. */
val TonightAnswers.labels: List<String> get() = listOf(mood.label, length.label, company.label)

data class TonightUiState(
    val loading: Boolean = false,
    val picks: List<TonightPick> = emptyList(),
    val error: String? = null,
    /** Dernières réponses données (gardées entre deux visites : l'accueil les résume sur la carte « Ce soir ? »). */
    val answers: TonightAnswers = DefaultTonightAnswers,
    /** Position de la proposition en cours de remplacement (« Autre proposition »), sinon null. */
    val replacing: Int? = null,
)

/** Un point de « Quoi de neuf ? » avec, quand la TV peut l'ouvrir, la chaîne à lancer. */
data class BriefItemUi(val text: String, val channel: MediaEntry?)

data class BriefUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val headline: String? = null,
    val items: List<BriefItemUi> = emptyList(),
    val error: String? = null,
    /** Heure du dernier résumé obtenu (« Mis à jour à 14:09 »), null tant qu'aucun n'est arrivé. */
    val updatedAtMillis: Long? = null,
)

/** Écrans de l'assistant (accessibles seulement quand l'IA est active). */
enum class AssistantMode(val title: String) {
    Tonight("Ce soir ?"),
}
