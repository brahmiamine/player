package fr.streamia.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.EpgProgram
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import fr.streamia.tv.domain.SeriesDetails
import fr.streamia.tv.domain.ServerCredentials
import fr.streamia.tv.domain.XtreamUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID

class XtreamRepository(context: Context) {
    private val appContext = context.applicationContext
    private val client = XtreamClient()
    private val cache = CatalogCache(context)
    private val credentialsStore = CredentialsStore(context)
    private val playlistStore = PlaylistStore(context)
    private val m3uParser = M3uParser()

    fun profiles(): List<PlaylistProfile> = playlistStore.loadAll()

    suspend fun openProfile(profileId: String): LoadedCatalog {
        val profile = playlistStore.find(profileId)
            ?: throw XtreamException("Cette liste n'existe plus.")
        return when (profile.kind) {
            PlaylistKind.Xtream -> openXtreamProfile(profile)
            PlaylistKind.M3u -> openM3uProfile(profile)
        }
    }

    suspend fun signIn(
        credentials: ServerCredentials,
        profileId: String? = null,
        profileName: String? = null,
    ): LoadedCatalog {
        XtreamUrlBuilder(credentials)
        val catalog = client.loadCatalog(credentials)
        val id = profileId ?: UUID.randomUUID().toString()
        val profile = PlaylistProfile(
            id = id,
            name = profileName.cleanName(defaultValue = credentials.serverUrl),
            kind = PlaylistKind.Xtream,
            serverUrl = credentials.serverUrl,
            username = credentials.username,
            password = credentials.password,
        )
        playlistStore.upsert(profile)
        credentialsStore.save(credentials)
        cache.save(id, catalog)
        return LoadedCatalog(catalog, credentials, CatalogSource.Network, id)
    }

    suspend fun refresh(credentials: ServerCredentials, profileId: String): LoadedCatalog {
        val catalog = client.loadCatalog(credentials)
        cache.save(profileId, catalog)
        return LoadedCatalog(catalog, credentials, CatalogSource.Network, profileId)
    }

    suspend fun importM3u(
        uri: Uri,
        profileId: String? = null,
        profileName: String? = null,
    ): LoadedCatalog = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw XtreamException("Impossible d'ouvrir le fichier M3U sélectionné.")
        val imported = input.use { stream ->
            m3uParser.parse(InputStreamReader(stream, StandardCharsets.UTF_8))
        }
        val id = profileId ?: UUID.randomUUID().toString()
        val profile = PlaylistProfile(
            id = id,
            name = profileName.cleanName(defaultValue = queryDisplayName(uri) ?: "Playlist M3U"),
            kind = PlaylistKind.M3u,
            serverUrl = imported.credentials.serverUrl,
            username = imported.credentials.username,
            password = imported.credentials.password,
            m3uUri = uri.toString(),
        )
        playlistStore.upsert(profile)
        credentialsStore.save(imported.credentials)
        cache.save(id, imported.catalog)

        val catalog = imported.catalog
        val summary = buildString {
            append("${imported.parsedEntries} médias")
            append(" · ${catalog.count(MediaType.Live)} chaînes")
            append(" · ${catalog.count(MediaType.Movie)} films")
            append(" · ${catalog.count(MediaType.Series)} séries")
            append(" · ${catalog.categories.size} catégories")
            if ("tvg-logo" in imported.detectedAttributes) append(" · logos")
            if ("tvg-id" in imported.detectedAttributes) append(" · EPG/TVG")
            if (imported.skippedEntries > 0) append(" · ${imported.skippedEntries} ignorés")
        }

        LoadedCatalog(
            catalog = catalog,
            credentials = imported.credentials,
            source = CatalogSource.Import,
            profileId = id,
            importSummary = summary,
        )
    }

    fun renameProfile(profileId: String, name: String): PlaylistProfile? =
        playlistStore.rename(profileId, name)

    suspend fun deleteProfile(profileId: String) {
        playlistStore.delete(profileId)
        cache.clear(profileId)
    }

    suspend fun seriesDetails(credentials: ServerCredentials, series: MediaEntry): SeriesDetails =
        client.loadSeriesDetails(credentials, series)

    suspend fun shortEpg(credentials: ServerCredentials, streamId: Int): List<EpgProgram> =
        client.loadShortEpg(credentials, streamId)

    suspend fun logout() {
        credentialsStore.clear()
    }

    private suspend fun openXtreamProfile(profile: PlaylistProfile): LoadedCatalog {
        val credentials = profile.credentialsOrNull()
            ?: throw XtreamException("Les identifiants de cette liste Xtream sont incomplets.")
        return try {
            val catalog = client.loadCatalog(credentials)
            credentialsStore.save(credentials)
            cache.save(profile.id, catalog)
            LoadedCatalog(catalog, credentials, CatalogSource.Network, profile.id)
        } catch (error: Exception) {
            val cached = cache.load(profile.id) ?: throw error
            credentialsStore.save(credentials)
            LoadedCatalog(cached, credentials, CatalogSource.Cache, profile.id)
        }
    }

    private suspend fun openM3uProfile(profile: PlaylistProfile): LoadedCatalog {
        val uri = profile.m3uUri?.let(Uri::parse)
            ?: throw XtreamException("Le fichier M3U associé à cette liste est introuvable.")
        return try {
            importM3u(uri, profile.id, profile.name)
        } catch (error: Exception) {
            val credentials = profile.credentialsOrNull() ?: throw error
            val cached = cache.load(profile.id) ?: throw error
            credentialsStore.save(credentials)
            LoadedCatalog(cached, credentials, CatalogSource.Cache, profile.id)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)?.takeIf(String::isNotBlank)
        }
    }.getOrNull()

    private fun String?.cleanName(defaultValue: String): String =
        this?.trim()?.takeIf(String::isNotBlank) ?: defaultValue
}

data class LoadedCatalog(
    val catalog: Catalog,
    val credentials: ServerCredentials,
    val source: CatalogSource,
    val profileId: String,
    val importSummary: String? = null,
)

enum class CatalogSource { Network, Cache, Import }
