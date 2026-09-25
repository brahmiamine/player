package fr.streamia.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connexion Internet de l'appareil, partagée par tout le processus.
 *
 * Sans elle, une coupure pendant l'utilisation laissait l'app en « Mode cache » et ses blocs
 * (guides, matchs, météo, logos…) figés sur leur dernier échec : rien ne signalait le retour du
 * réseau, chaque bloc attendait son prochain créneau, parfois jusqu'à la relance de l'app.
 *
 * « En ligne » = réseau par défaut avec accès Internet validé par Android. [reconnections]
 * augmente à chaque retour en ligne après une coupure : c'est le signal pour tout recharger.
 */
class NetworkMonitor private constructor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnlineNow())
    private val _reconnections = MutableStateFlow(0)

    val online: StateFlow<Boolean> = _online.asStateFlow()
    val reconnections: StateFlow<Int> = _reconnections.asStateFlow()

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                update(capabilities.isValidatedInternet())
            }

            // Un autre réseau (Wi-Fi ↔ Ethernet) peut avoir pris le relais : on relit l'état actuel.
            override fun onLost(network: Network) = update(isOnlineNow())
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivity?.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                connectivity?.registerNetworkCallback(request, callback)
            }
        }
    }

    @Synchronized
    private fun update(online: Boolean) {
        val wasOnline = _online.value
        _online.value = online
        if (online && !wasOnline) _reconnections.value += 1
    }

    private fun isOnlineNow(): Boolean = runCatching {
        val manager = connectivity ?: return@runCatching true
        manager.getNetworkCapabilities(manager.activeNetwork)?.isValidatedInternet() == true
    }.getOrDefault(true)

    private fun NetworkCapabilities.isValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    companion object {
        @Volatile private var instance: NetworkMonitor? = null

        fun get(context: Context): NetworkMonitor = instance ?: synchronized(this) {
            instance ?: NetworkMonitor(context.applicationContext).also { instance = it }
        }
    }
}
