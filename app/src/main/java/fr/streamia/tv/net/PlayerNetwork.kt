package fr.streamia.tv.net

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.datasource.okhttp.OkHttpDataSource
import fr.streamia.tv.data.PlayerNetworkMode
import org.chromium.net.CronetEngine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pile réseau du lecteur. En mode Auto, Cronet (la pile de Chrome : HTTP/2, DNS et connexions
 * mieux réutilisés) quand le boîtier en offre un via Google Play Services, sinon OkHttp. Le choix
 * se fait à chaque ouverture de flux : le lecteur Direct, créé avant la fin du chargement de
 * Cronet, en profite dès qu'il est prêt.
 */
@UnstableApi
object PlayerNetwork {
    /** User-Agent courant (préfixe Mozilla) : certains serveurs IPTV brident les agents inconnus. */
    const val COMPAT_USER_AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Streamia-TV/1.5"

    @Volatile private var engine: CronetEngine? = null
    private val probed = AtomicBoolean(false)
    private val executor: Executor by lazy { Executors.newFixedThreadPool(4) }

    /** Dernière pile réellement utilisée pour ouvrir un flux ("Cronet" ou "OkHttp"), affichée dans le diagnostic. */
    @Volatile var lastUsed: String? = null
        private set

    /** Charge Cronet une fois (quelques centaines de ms) : à appeler hors du thread principal. */
    fun warmUp(context: Context) {
        if (!probed.compareAndSet(false, true)) return
        engine = runCatching { CronetUtil.buildCronetEngine(context.applicationContext, COMPAT_USER_AGENT, true) }.getOrNull()
    }

    fun factory(mode: PlayerNetworkMode): HttpDataSource.Factory = SwitchingFactory(mode)

    private class SwitchingFactory(private val mode: PlayerNetworkMode) : HttpDataSource.Factory {
        private var defaults: Map<String, String> = emptyMap()

        override fun createDataSource(): HttpDataSource {
            val cronet = engine.takeIf { mode == PlayerNetworkMode.Auto }
            val okHttp = OkHttpDataSource.Factory(HttpClients.player)
                .setUserAgent(if (mode == PlayerNetworkMode.Auto) COMPAT_USER_AGENT else HttpClients.USER_AGENT)
            val chosen: HttpDataSource.Factory = if (cronet == null) {
                lastUsed = "OkHttp"
                okHttp
            } else {
                lastUsed = "Cronet"
                CronetDataSource.Factory(cronet, executor)
                    .setUserAgent(COMPAT_USER_AGENT)
                    .setConnectionTimeoutMs(4_000)
                    .setReadTimeoutMs(30_000)
                    .setResetTimeoutOnRedirects(true)
                    .setFallbackFactory(okHttp)
            }
            return chosen.setDefaultRequestProperties(defaults).createDataSource()
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            defaults = defaultRequestProperties.toMap()
            return this
        }
    }
}
