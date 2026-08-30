package fr.streamia.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.CatalogSource
import fr.streamia.tv.data.LoadedCatalog
import fr.streamia.tv.data.XtreamRepository
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.LiveChannel
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.adjacentTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamiaViewModel(private val repository: XtreamRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamiaUiState())
    val uiState: StateFlow<StreamiaUiState> = _uiState.asStateFlow()

    init {
        restoreSession()
    }

    fun signIn(server: String, username: String, password: String) {
        if (_uiState.value.busy) return
        val credentials = ServerCredentials(server.trim(), username.trim(), password)
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.signIn(credentials) }
                .onSuccess(::showCatalog)
                .onFailure { error ->
                    _uiState.update { it.copy(busy = false, message = error.safeMessage()) }
                }
        }
    }

    fun refresh() {
        val credentials = _uiState.value.credentials ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { repository.refresh(credentials) }
                .onSuccess(::showCatalog)
                .onFailure { error ->
                    _uiState.update { it.copy(busy = false, message = error.safeMessage()) }
                }
        }
    }

    fun openChannel(channel: LiveChannel) {
        _uiState.update { it.copy(screen = StreamiaScreen.Player(channel), message = null) }
    }

    fun closePlayer() {
        _uiState.update { it.copy(screen = StreamiaScreen.Browser) }
    }

    fun zap(delta: Int) {
        val state = _uiState.value
        val current = (state.screen as? StreamiaScreen.Player)?.channel ?: return
        val next = state.catalog?.channelsIn(current.categoryId)?.adjacentTo(current.id, delta) ?: return
        _uiState.update { it.copy(screen = StreamiaScreen.Player(next)) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = StreamiaUiState(booting = false, screen = StreamiaScreen.Login)
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            runCatching { repository.restore() }
                .onSuccess { loaded ->
                    if (loaded == null) {
                        _uiState.value = StreamiaUiState(booting = false, screen = StreamiaScreen.Login)
                    } else {
                        showCatalog(loaded)
                    }
                }
                .onFailure { error ->
                    _uiState.value = StreamiaUiState(
                        booting = false,
                        screen = StreamiaScreen.Login,
                        message = error.safeMessage(),
                    )
                }
        }
    }

    private fun showCatalog(loaded: LoadedCatalog) {
        _uiState.value = StreamiaUiState(
            booting = false,
            busy = false,
            screen = StreamiaScreen.Browser,
            catalog = loaded.catalog,
            credentials = loaded.credentials,
            offline = loaded.source == CatalogSource.Cache,
        )
    }

    private fun Throwable.safeMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Une erreur inattendue s'est produite."
}

data class StreamiaUiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    val screen: StreamiaScreen = StreamiaScreen.Login,
    val catalog: Catalog? = null,
    val credentials: ServerCredentials? = null,
    val offline: Boolean = false,
    val message: String? = null,
)

sealed interface StreamiaScreen {
    data object Login : StreamiaScreen
    data object Browser : StreamiaScreen
    data class Player(val channel: LiveChannel) : StreamiaScreen
}

class StreamiaViewModelFactory(private val repository: XtreamRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StreamiaViewModel(repository) as T
}
