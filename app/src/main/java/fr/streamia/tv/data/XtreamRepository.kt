package fr.streamia.tv.data

import android.content.Context
import android.net.Uri
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class XtreamRepository(context: Context) {
    private val appContext = context.applicationContext
    private val client = XtreamClient()
    private val cache = CatalogCache(context)
    private val credentialsStore = CredentialsStore(context)
    private val m3uParser = M3uParser()

    suspend fun restore(): LoadedCatalog? {
        val credentials = credentialsStore.load() ?: return null
        return try {
            val catalog = client.loadCatalog(credentials)
            cache.save(catalog)
            LoadedCatalog(catalog, credentials, CatalogSource.Network)
        } catch (error: Exception) {
            val cached = cache.load() ?: throw error
            LoadedCatalog(cached, credentials, CatalogSource.Cache)
        }
    }

    suspend fun signIn(credentials: ServerCredentials): LoadedCatalog {
        XtreamUrlBuilder(credentials)
        val catalog = client.loadCatalog(credentials)
        credentialsStore.save(credentials)
        cache.save(catalog)
        return LoadedCatalog(catalog, credentials, CatalogSource.Network)
    }

    suspend fun refresh(credentials: ServerCredentials): LoadedCatalog {
        val catalog = client.loadCatalog(credentials)
        cache.save(catalog)
        return LoadedCatalog(catalog, credentials, CatalogSource.Network)
    }

    suspend fun importM3u(uri: Uri): LoadedCatalog = withContext(Dispatchers.IO) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw XtreamException("Impossible d'ouvrir le fichier M3U sélectionné.")
        val imported = input.use { stream ->
            m3uParser.parse(InputStreamReader(stream, StandardCharsets.UTF_8))
        }
        credentialsStore.save(imported.credentials)
        cache.save(imported.catalog)
        LoadedCatalog(
            catalog = imported.catalog,
            credentials = imported.credentials,
            source = CatalogSource.Import,
            importSummary = "${imported.parsedEntries} médias importés" +
                if (imported.skippedEntries > 0) " · ${imported.skippedEntries} ignorés" else "",
        )
    }

    suspend fun seriesDetails(credentials: ServerCredentials, series: MediaEntry): SeriesDetails =
        client.loadSeriesDetails(credentials, series)

    suspend fun shortEpg(credentials: ServerCredentials, streamId: Int): List<EpgProgram> =
        client.loadShortEpg(credentials, streamId)

    suspend fun logout() {
        credentialsStore.clear()
        cache.clear()
    }
}

data class LoadedCatalog(
    val catalog: Catalog,
    val credentials: ServerCredentials,
    val source: CatalogSource,
    val importSummary: String? = null,
)

enum class CatalogSource { Network, Cache, Import }
