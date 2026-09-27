package fr.streamia.tv.ui

import androidx.lifecycle.viewModelScope
import fr.streamia.tv.data.HomePlace
import fr.streamia.tv.data.HomeWeatherClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Météo de l'en-tête de l'accueil : au plus une requête toutes les 30 min (5 min après un échec).
 */
internal class WeatherController(host: StreamiaStateHolder) : StreamiaController(host) {
    private val weatherClient = HomeWeatherClient()

    private var weatherNextCheckAtMillis = 0L

    private var weatherJob: Job? = null

    /** Météo de l'en-tête : au plus une requête toutes les 30 min (5 min après un échec). */
    fun refreshWeatherIfStale() {
        if (System.currentTimeMillis() < weatherNextCheckAtMillis || weatherJob?.isActive == true) return
        weatherJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val place = _uiState.value.appSettings.homePlace
                        ?: _homeState.value.weatherPlace
                        ?: weatherClient.locateByIp()
                        ?: error("Localisation impossible.")
                    place to weatherClient.currentWeather(place)
                }
            }
            result.onSuccess { (place, weather) ->
                _homeState.update { it.copy(weatherPlace = place, weather = weather) }
            }
            weatherNextCheckAtMillis = System.currentTimeMillis() + if (result.isSuccess) 30 * 60_000L else 5 * 60_000L
        }
    }

    /** Réseau revenu : l'échéance après échec est oubliée. */
    fun retryNow() {
        weatherNextCheckAtMillis = 0L
        refreshWeatherIfStale()
    }

    /** Lieu changé : la requête en cours porte sur l'ancien, la prochaine part tout de suite. */
    fun forgetPending() {
        weatherJob?.cancel()
        weatherNextCheckAtMillis = 0L
    }

    suspend fun searchCities(query: String): List<HomePlace> =
        withContext(Dispatchers.IO) { runCatching { weatherClient.searchCities(query) }.getOrDefault(emptyList()) }
}
