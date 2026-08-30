package fr.streamia.tv.data

import android.content.Context
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder

class XtreamRepository(context: Context) {
    private val client = XtreamClient()
    private val cache = CatalogCache(context)
    private val credentialsStore = CredentialsStore(context)

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

    suspend fun logout() {
        credentialsStore.clear()
        cache.clear()
    }
}

data class LoadedCatalog(
    val catalog: Catalog,
    val credentials: ServerCredentials,
    val source: CatalogSource,
)

enum class CatalogSource { Network, Cache }
