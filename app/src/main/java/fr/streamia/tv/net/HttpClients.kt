package fr.streamia.tv.net

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

    private val timedClients = ConcurrentHashMap<Pair<Long, Long>, OkHttpClient>()

    /** Client aux délais donnés, qui partage pool de connexions et répartiteur avec [base]. */
    fun timed(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
        timedClients.getOrPut(connectTimeoutMs to readTimeoutMs) {
            base.newBuilder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

    /**
     * Requête GET (ou POST si [body] est fourni). Adresse invalide : [IOException], comme
     * l'ancienne pile `HttpURLConnection` (MalformedURLException). La réponse doit être fermée.
     */
    @Throws(IOException::class)
    fun execute(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Long = 12_000,
        readTimeoutMs: Long = 30_000,
        body: Pair<String, String>? = null,
    ): Response {
        val request = try {
            Request.Builder().url(url).apply {
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) header("User-Agent", USER_AGENT)
                headers.forEach { (name, value) -> header(name, value) }
                body?.let { (contentType, content) -> post(content.toRequestBody(contentType.toMediaType())) }
            }.build()
        } catch (invalid: IllegalArgumentException) {
            throw IOException("Adresse invalide : ${invalid.message}", invalid)
        }
        return timed(connectTimeoutMs, readTimeoutMs).newCall(request).execute()
    }

    /**
     * Texte d'une réponse (UTF-8). Code hors 2xx : [IOException] dont le message est donné par
     * [errorMessage] (reçoit le code HTTP).
     */
    @Throws(IOException::class)
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Long = 12_000,
        readTimeoutMs: Long = 30_000,
        body: Pair<String, String>? = null,
        errorMessage: (Int) -> String = { code -> "Le serveur a répondu avec le code $code." },
    ): String = execute(url, headers, connectTimeoutMs, readTimeoutMs, body).use { response ->
        if (!response.isSuccessful) throw IOException(errorMessage(response.code))
        response.body?.bytes()?.toString(Charsets.UTF_8) ?: ""
    }
}
