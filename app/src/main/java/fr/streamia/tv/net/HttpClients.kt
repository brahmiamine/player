package fr.streamia.tv.net

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Un seul client OkHttp pour toute l'application : même pool de connexions (TLS et TCP réutilisés
 * entre l'API Xtream, les images et le lecteur, qui visent souvent le même serveur), même
 * répartiteur de requêtes. Chaque usage n'ajuste que ses délais et son cache via [OkHttpClient.newBuilder],
 * qui partage pool et répartiteur avec [base].
 */
object HttpClients {
    const val USER_AGENT = "Streamia-TV/1.5"

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Lecteur Media3 : connexion rapide (zapping), lecture longue. */
    val player: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** API Xtream, guides, métadonnées : réponses parfois volumineuses (catalogues, XMLTV). */
    val api: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var artworkClient: OkHttpClient? = null

    /** Logos et affiches : délais courts, cache HTTP disque de 64 Mo. */
    fun artwork(context: Context): OkHttpClient = artworkClient ?: synchronized(this) {
        artworkClient ?: base.newBuilder()
            .cache(Cache(File(context.applicationContext.cacheDir, "artwork-http"), 64L * 1024L * 1024L))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
            .also { artworkClient = it }
    }
}
