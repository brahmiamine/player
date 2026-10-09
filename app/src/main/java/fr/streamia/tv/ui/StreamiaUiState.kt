package fr.streamia.tv.ui

import fr.streamia.tv.beinsports.ResolvedBeinProgrammeItem
import fr.streamia.tv.data.AppSettings
import fr.streamia.tv.data.CurrentWeather
import fr.streamia.tv.data.HomeBlock
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.VodSortOrder
import fr.streamia.tv.data.PlaylistProfile
import fr.streamia.tv.data.UpdateCheckResult
import fr.streamia.tv.data.UserLibrarySnapshot
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgGuide
import fr.streamia.tv.domain.EpgNowContext
import fr.streamia.tv.domain.MediaDetails
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.liveonsat.ResolvedLiveOnSatMatch
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeItem
import fr.streamia.tv.tvprogramme.ResolvedTvProgrammeNowItem
import fr.streamia.tv.ukguide.ResolvedUkProgrammeItem
import fr.streamia.tv.recommendation.RecommendationRow
import fr.streamia.tv.recommendation.RecommendedMedia
import java.time.LocalDate

data class StreamiaUiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    val testingConnection: Boolean = false,
    val testSucceeded: Boolean = false,
    val catalogHydrating: Boolean = false,
    val screen: StreamiaScreen = StreamiaScreen.Login,
    val rawCatalog: Catalog? = null,
    val catalog: Catalog? = null,
    val credentials: ServerCredentials? = null,
    val activeProfileId: String? = null,
    val profiles: List<PlaylistProfile> = emptyList(),
    val library: UserLibrarySnapshot = UserLibrarySnapshot(),
    val appSettings: AppSettings = AppSettings(),
    /** Code parental saisi correctement pendant cette session (redevient false à la relance de l'app). */
    val parentalUnlocked: Boolean = false,
    val updateChecking: Boolean = false,
    val updateCheck: UpdateCheckResult? = null,
    val offline: Boolean = false,
    val message: String? = null,
    val mediaDetails: MediaDetails? = null,
    val seriesDetails: SeriesDetails? = null,
    val epgGuide: EpgGuide? = null,
    /** Guide de la journée courante (horaires déjà décalés) : programme en cours de la liste Direct. */
    val todayEpgGuide: EpgGuide? = null,
    val epgAvailableDates: List<LocalDate> = emptyList(),
    val epgSelectedDate: LocalDate? = null,
    val epgLoading: Boolean = false,
    val similarMedia: List<RecommendedMedia> = emptyList(),
    /** Description de la fiche ouverte traduite par l'IA (null = pas de traduction). */
    val aiPlot: String? = null,
    /** Ordre des similaires proposé par l'IA (clés des entrées) ; null = ordre du moteur. */
    val aiSimilarKeys: List<String>? = null,
    /** Premier calcul de « Films/Séries similaires » en cours pour la fiche ouverte (cartes fantômes). */
    val similarLoading: Boolean = false,
    val resumePositionMs: Long = 0,
    val browserType: MediaType? = null,
    val browserCategoryId: String? = null,
    /**
     * Catégories dont une page est en cours de lecture SQLite, clés [Catalog.categoryKey]. Permet à
     * l'interface d'afficher « Chargement… » plutôt que « Aucun contenu » pendant l'ouverture d'une
     * catégorie Films/Séries encore jamais parcourue.
     */
    val loadingCategoryKeys: Set<String> = emptySet(),
    /** Films/Séries paginés : clés des entrées dans l'ordre des pages lues en base, par catégorie et par tri (voir vodPageKey). */
    val vodPages: Map<String, List<MediaEntry>> = emptyMap(),
    /** Catégories dont le dernier chargement de page a échoué (message + nouvel essai dans la grille). */
    val categoryLoadErrors: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchType: MediaType? = null,
    val contentReturnContext: ContentReturnContext? = null,
    /** Liste quittée par « Changer de liste » : Retour dans le gestionnaire la rouvre. */
    val returnProfileId: String? = null,
    /**
     * Pile des écrans de menu traversés (Accueil, Direct/Films/Séries, Paramètres, Recherche, EPG,
     * Outils…). Elle permet à Retour de revenir à l'écran d'où l'on vient au lieu de l'accueil.
     */
    val menuBackStack: List<StreamiaScreen> = emptyList(),
    /** Carte de l'accueil à refocaliser au retour (voir [HomeFocusTarget]). */
    val homeFocusTarget: HomeFocusTarget? = null,
    val lastViewedEntry: MediaEntry? = null,
)

/**
 * État de l'accueil (guides tiers, matchs, recommandations, météo), dans son propre flux : ses
 * mises à jour fréquentes (lots de matchs rapprochés, guides, rangées JustWatch) ne réémettent plus
 * l'état de toute l'application ni ne recomposent les autres écrans.
 */
data class HomeUiState(
    val homeRecommendationRows: List<RecommendationRow> = emptyList(),
    /** Rangées JustWatch, chargées à part (cache disque puis actualisation) : voir loadJustWatchRows. */
    val homeJustWatchRows: List<RecommendationRow> = emptyList(),
    /** Blocs de l'accueil dont le premier chargement n'est pas fini (squelette affiché). */
    val homePendingBlocks: Set<HomeBlock> = setOf(
        HomeBlock.TvProgrammeNow, HomeBlock.TvProgrammeTonight, HomeBlock.BeinSportsNow, HomeBlock.BeinSportsNext,
        HomeBlock.UkGuideNow, HomeBlock.UkGuideNext, HomeBlock.Recommendations,
    ),
    /** Premier chargement des matchs liveonsat pas encore fini (squelette « Matchs en direct »). */
    val liveOnSatPending: Boolean = true,
    /** Rapprochement des chaînes liveonsat en cours (page Matchs et accueil : chaînes fantômes). */
    val liveOnSatResolving: Boolean = false,
    val homeTvProgrammeNow: List<ResolvedTvProgrammeNowItem> = emptyList(),
    val homeTvProgrammeTonight: List<ResolvedTvProgrammeItem> = emptyList(),
    val homeBeinSportsNow: List<ResolvedBeinProgrammeItem> = emptyList(),
    val homeBeinSportsNext: List<ResolvedBeinProgrammeItem> = emptyList(),
    val homeUkGuideNow: List<ResolvedUkProgrammeItem> = emptyList(),
    val homeUkGuideNext: List<ResolvedUkProgrammeItem> = emptyList(),
    /** Ville effective de l'en-tête (réglée, ou détectée d'après la connexion). */
    val weatherPlace: HomePlace? = null,
    val weather: CurrentWeather? = null,
    val liveOnSatMatches: List<ResolvedLiveOnSatMatch> = emptyList(),
    val liveOnSatLoading: Boolean = false,
    val liveOnSatError: String? = null,
    val liveOnSatFetchedAtEpochMillis: Long? = null,
)

/**
 * État du lecteur qui change souvent (EPG courant rafraîchi toutes les 30 s, zap en cours) : dans
 * son propre flux, lu seulement par l'écran lecteur, pour ne pas invalider la racine de l'app.
 */
data class PlayerUiState(
    val epg: EpgNowContext = EpgNowContext(),
    /** Chaîne annoncée par un zap rapide en cours, pas encore lancée (voir [StreamiaViewModel.zap]). */
    val pendingZapEntry: MediaEntry? = null,
)

sealed interface StreamiaScreen {
    data object Login : StreamiaScreen
    data object Home : StreamiaScreen
    data object Browser : StreamiaScreen
    data object Settings : StreamiaScreen
    data object About : StreamiaScreen
    data object ParentalControl : StreamiaScreen
    data object Search : StreamiaScreen
    data object Epg : StreamiaScreen
    data object Organizer : StreamiaScreen
    data object LiveMatches : StreamiaScreen
    data class MovieDetails(val movie: MediaEntry) : StreamiaScreen
    data class Series(val series: MediaEntry) : StreamiaScreen
    data class Player(
        val entry: MediaEntry,
        val returnToSeries: Boolean = false,
        /** Lecture lancée depuis la fiche du film : Retour rouvre la fiche au lieu de la liste. */
        val returnToDetails: Boolean = false,
    ) : StreamiaScreen
}

/** Clé des pages Films/Séries d'une catégorie pour un tri donné : changer de tri n'écrase pas l'autre ordre. */
internal fun vodPageKey(type: MediaType, categoryId: String, order: VodSortOrder): String =
    Catalog.categoryKey(type, categoryId) + "|" + order.name

/**
 * Pages Films/Séries gardées en mémoire, de la plus récente (fin de [pages]) à la plus ancienne,
 * tant que leur total d'entrées reste sous [maxEntries]. La plus récente et celles de la
 * catégorie [protectedCategoryKey] (affichée à l'écran) sont toujours gardées. L'ordre relatif
 * des pages retenues est conservé.
 */
internal fun <T> retainedVodPages(
    pages: Map<String, List<T>>,
    protectedCategoryKey: String?,
    maxEntries: Int,
): Map<String, List<T>> {
    if (pages.values.sumOf { it.size } <= maxEntries) return pages
    val protectedPrefix = protectedCategoryKey?.let { "$it|" }
    val keptKeys = HashSet<String>()
    var total = 0
    var full = false
    pages.entries.reversed().forEachIndexed { index, (key, keys) ->
        val isProtected = protectedPrefix != null && key.startsWith(protectedPrefix)
        if (!full && index > 0 && total + keys.size > maxEntries) full = true
        if (index == 0 || isProtected || !full) {
            keptKeys += key
            total += keys.size
        }
    }
    return pages.filterKeys { it in keptKeys }
}

/**
 * Plafond d'entrées Films/Séries matérialisées par les pages de catégories (≈ 10 pages de 500).
 * Au-delà, les pages les moins récemment ouvertes sont libérées puis relues depuis SQLite au besoin.
 */
internal const val MAX_MATERIALIZED_VOD_ENTRIES = 5_000
