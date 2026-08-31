package fr.streamia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import fr.streamia.tv.ui.theme.MutedInk
import fr.streamia.tv.ui.theme.Night
import fr.streamia.tv.ui.theme.StreamiaTheme

@Composable
fun StreamiaApp(viewModel: StreamiaViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    StreamiaTheme {
        ResponsiveTvViewport {
            when {
                state.booting -> BootScreen()

                state.screen is StreamiaScreen.Login -> LoginScreen(
                    profiles = state.profiles,
                    busy = state.busy,
                    message = state.message,
                    onOpenProfile = viewModel::openProfile,
                    onSignIn = viewModel::signIn,
                    onImportM3u = viewModel::importM3u,
                    onImportM3uUrl = viewModel::importM3uUrl,
                    onSaveM3uSettings = viewModel::saveM3uSettings,
                    onRenameProfile = viewModel::renameProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onDismissMessage = viewModel::dismissMessage,
                )

                state.screen is StreamiaScreen.Home && state.catalog != null -> HomeScreen(
                    catalog = state.catalog!!,
                    profileName = state.profiles.firstOrNull { it.id == state.activeProfileId }?.name,
                    offline = state.offline,
                    busy = state.busy,
                    onOpenSection = viewModel::openSection,
                    onSettings = viewModel::showSettings,
                    onSearch = viewModel::showSearch,
                    onEpg = viewModel::showEpg,
                    onRefresh = viewModel::refresh,
                    onChangePlaylist = viewModel::logout,
                )

                state.screen is StreamiaScreen.Browser && state.catalog != null && state.credentials != null -> BrowserScreen(
                    catalog = state.catalog!!,
                    credentials = state.credentials!!,
                    library = state.library,
                    offline = state.offline,
                    busy = state.busy,
                    message = state.message,
                    initialType = state.browserType,
                    initialCategoryId = state.browserCategoryId,
                    onEntrySelected = viewModel::openEntry,
                    onToggleEntryFavorite = viewModel::toggleEntryFavorite,
                    onToggleCategoryFavorite = viewModel::toggleCategoryFavorite,
                    onLocationChanged = viewModel::rememberBrowserLocation,
                    onHome = viewModel::showHome,
                    onSearch = viewModel::showSearch,
                    onEpg = viewModel::showEpg,
                    onSettings = viewModel::showSettings,
                    onDismissMessage = viewModel::dismissMessage,
                )

                state.screen is StreamiaScreen.Settings -> SettingsScreen(
                    busy = state.busy,
                    historyCount = state.library.history.size,
                    onSearch = viewModel::showSearch,
                    onEpg = viewModel::showEpg,
                    onOrganizer = viewModel::showOrganizer,
                    onRefresh = viewModel::refresh,
                    onClearHistory = viewModel::clearHistory,
                    onChangePlaylist = viewModel::logout,
                    onBack = viewModel::showHome,
                )

                state.screen is StreamiaScreen.Search && state.catalog != null -> SearchScreen(
                    catalog = state.catalog!!,
                    favoriteEntries = state.library.favoriteEntries,
                    favoriteCategories = state.library.favoriteCategories,
                    onOpenEntry = viewModel::openEntry,
                    onOpenCategory = viewModel::openCategory,
                    onToggleEntryFavorite = viewModel::toggleEntryFavorite,
                    onToggleCategoryFavorite = viewModel::toggleCategoryFavorite,
                    onBack = viewModel::showHome,
                )

                state.screen is StreamiaScreen.Epg && state.catalog != null -> EpgScreen(
                    catalog = state.catalog!!,
                    guide = state.epgGuide,
                    loading = state.epgLoading,
                    message = state.message,
                    onOpenChannel = viewModel::openEntry,
                    onReload = viewModel::reloadEpg,
                    onBack = viewModel::showHome,
                )

                state.screen is StreamiaScreen.Organizer && state.catalog != null -> OrganizerScreen(
                    catalog = state.catalog!!,
                    onCategoryOrderChanged = viewModel::setCategoryOrder,
                    onMoveEntries = viewModel::moveEntries,
                    onResetMoves = viewModel::resetEntryMoves,
                    onBack = viewModel::showSettings,
                )

                state.screen is StreamiaScreen.MovieDetails -> {
                    val movie = (state.screen as StreamiaScreen.MovieDetails).movie
                    val resume = state.library.history.firstOrNull { it.entry.key == movie.key }?.positionMs ?: 0L
                    MovieDetailsScreen(
                        movie = movie,
                        details = state.mediaDetails,
                        busy = state.busy,
                        message = state.message,
                        favorite = movie.key in state.library.favoriteEntries,
                        resumePositionMs = resume,
                        onPlay = { viewModel.playMovie(movie) },
                        onToggleFavorite = { viewModel.toggleEntryFavorite(movie) },
                        onBack = viewModel::closeDetails,
                    )
                }

                state.screen is StreamiaScreen.Series && state.credentials != null -> {
                    val series = (state.screen as StreamiaScreen.Series).series
                    SeriesScreen(
                        series = series,
                        details = state.seriesDetails,
                        busy = state.busy,
                        message = state.message,
                        favorite = series.key in state.library.favoriteEntries,
                        onToggleFavorite = { viewModel.toggleEntryFavorite(series) },
                        onEpisodeSelected = { episode -> viewModel.playEpisode(series, episode) },
                        onBack = viewModel::closeSeries,
                        onRetry = { viewModel.openEntry(series) },
                    )
                }

                state.screen is StreamiaScreen.Player && state.catalog != null && state.credentials != null -> {
                    val playerScreen = state.screen as StreamiaScreen.Player
                    PlayerScreen(
                        catalog = state.catalog!!,
                        credentials = state.credentials!!,
                        entry = playerScreen.entry,
                        epg = state.epg,
                        resumePositionMs = state.resumePositionMs,
                        onBack = {
                            LiveBrowserReturnState.remember(playerScreen.entry)
                            viewModel.closePlayer()
                        },
                        onZap = viewModel::zap,
                        onEntrySelected = viewModel::openEntry,
                        onProgress = viewModel::recordPlayback,
                    )
                }

                else -> BootScreen()
            }
        }
    }
}

@Composable
private fun BootScreen() {
    Box(Modifier.fillMaxSize().background(Night), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StreamiaLogo()
            Spacer(Modifier.height(22.dp))
            Text("Préparation de votre télévision…", color = MutedInk, fontSize = 17.sp)
        }
    }
}
