package fr.streamia.tv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.streamia.tv.domain.Catalog
import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Intégration réelle de la couche catalogue : écriture SQLite, lecture légère, pagination par
 * curseur (keyset), recherche plein texte et lecture par lots — sans réseau ni dépôt simulé. C'est
 * ici que se jouent les performances des très gros catalogues ; ces tests garantissent qu'une page,
 * une recherche ou un lot de clés reste correct malgré le stockage normalisé et indexé.
 */
@RunWith(AndroidJUnit4::class)
class CatalogCacheIntegrationTest {

    private lateinit var context: Context
    private lateinit var cache: CatalogCache
    private val profileId = "itest-${System.nanoTime()}"
    private val category = MediaCategory("films", "Films", MediaType.Movie)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cache = CatalogCache(context)
    }

    @After
    fun tearDown() = runBlocking {
        cache.clear(profileId)
    }

    private fun movie(id: Int, name: String, categoryId: String = "films", number: Int = id) =
        MediaEntry(id = id, name = name, categoryId = categoryId, iconUrl = null, number = number, type = MediaType.Movie)

    private fun catalogOf(vararg entries: MediaEntry): Catalog = Catalog(
        categories = listOf(category),
        entries = entries.toList(),
        totalCounts = mapOf(MediaType.Movie to entries.size),
        categoryCounts = mapOf(Catalog.categoryKey(MediaType.Movie, "films") to entries.size),
    )

    @Test
    fun saveThenLoadLightweightReturnsCategoriesAndCounts() = runBlocking {
        cache.save(profileId, catalogOf(movie(1, "Film 1"), movie(2, "Film 2"), movie(3, "Film 3")))

        val loaded = cache.load(profileId)

        assertNotNull(loaded)
        assertEquals(listOf(category), loaded!!.categories)
        assertEquals(3, loaded.count(MediaType.Movie))
        // Lecture légère : pas toutes les entrées matérialisées, seulement les récentes de contexte.
        assertTrue(loaded.entries.size <= 3)
    }

    @Test
    fun keysetPaginationReturnsOrderedNonOverlappingPages() = runBlocking {
        val entries = (1..1200).map { movie(it, "Film $it", number = it) }
        cache.save(profileId, catalogOf(*entries.toTypedArray()))

        val first = cache.loadCategoryPage(profileId, MediaType.Movie, "films", offset = 0, limit = 500, order = VodSortOrder.Provider)
        val second = cache.loadCategoryPage(
            profileId, MediaType.Movie, "films",
            offset = first.nextOffset, limit = 500, order = VodSortOrder.Provider,
            afterKey = first.entries.last().key,
        )
        val third = cache.loadCategoryPage(
            profileId, MediaType.Movie, "films",
            offset = second.nextOffset, limit = 500, order = VodSortOrder.Provider,
            afterKey = second.entries.last().key,
        )

        assertEquals(500, first.entries.size)
        assertEquals(500, second.entries.size)
        assertEquals(200, third.entries.size)
        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        assertFalse(third.hasMore)

        // Ordre fournisseur respecté et aucune entrée perdue ni dupliquée entre les pages.
        val all = first.entries + second.entries + third.entries
        assertEquals((1..1200).toList(), all.map(MediaEntry::number))
        assertEquals(1200, all.map(MediaEntry::key).toSet().size)
    }

    @Test
    fun searchFindsPrefixMatchesAndIgnoresAccents() = runBlocking {
        cache.save(
            profileId,
            catalogOf(
                movie(1, "TF1 Séries Films"),
                movie(2, "Équipe TV"),
                movie(3, "Documentaire"),
            ),
        )

        val byPrefix = cache.search(profileId, "film", MediaType.Movie)
        assertTrue(byPrefix.any { it.id == 1 })

        val byAccent = cache.search(profileId, "equipe", MediaType.Movie)
        assertTrue(byAccent.any { it.id == 2 })
    }

    @Test
    fun loadEntriesByKeysReturnsRequestedOrder() = runBlocking {
        cache.save(profileId, catalogOf(movie(1, "Un"), movie(2, "Deux"), movie(3, "Trois")))

        val result = cache.loadEntriesByKeys(
            profileId,
            keys = setOf("Movie:3", "Movie:1"),
        )

        assertEquals(listOf("Movie:3", "Movie:1"), result.map(MediaEntry::key))
    }

    @Test
    fun alphabeticalPagingUsesSortKey() = runBlocking {
        val entries = listOf("Zèbre", "Ananas", "Mangue", "Banane").mapIndexed { index, name ->
            movie(index + 1, name, number = index + 1)
        }
        cache.save(profileId, catalogOf(*entries.toTypedArray()))

        val page = cache.loadCategoryPage(profileId, MediaType.Movie, "films", offset = 0, limit = 10, order = VodSortOrder.Alphabetical)

        assertEquals(listOf("Ananas", "Banane", "Mangue", "Zèbre"), page.entries.map(MediaEntry::name))
    }
}
