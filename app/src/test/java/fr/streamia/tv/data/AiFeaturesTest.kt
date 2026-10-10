package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry
import fr.streamia.tv.domain.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFeaturesTest {
    private fun movie(id: Int, name: String, category: String = "1") =
        MediaEntry(id = id, name = name, type = MediaType.Movie, categoryId = category, iconUrl = null, number = id)

    @Test
    fun jsonIsExtractedFromFencesAndThinkingBlocks() {
        val obj = extractJsonObject("<think>{\"x\":1}</think>```json\n{\"a\":\"b\"}\n```")
        assertEquals("b", obj?.getString("a"))
        assertNull(extractJsonObject("pas de json"))
    }

    @Test
    fun searchPlanIsParsedAndForcedTypeWins() {
        val answer = """{"type":"movie","genres":["action"],"regions":[],"keywords":[],"titles":["Heat"],"years":[1999,1990],"sort":"recent","summary":"Action des années 90"}"""
        val plan = parseSearchPlan(answer)!!
        assertEquals(MediaType.Movie, plan.type)
        assertEquals(1990, plan.yearFrom)
        assertEquals(1999, plan.yearTo)
        assertEquals(SearchSort.Recent, plan.sort)
        assertEquals(MediaType.Series, parseSearchPlan(answer, MediaType.Series)!!.type)
        assertNull(parseSearchPlan("""{"type":"any","genres":[],"titles":[]}"""))
    }

    @Test
    fun categoriesAreMatchedByNameWithAccentsAndVariants() {
        val categories = listOf(
            MediaCategory("1", "FILMS | ACTION", MediaType.Movie),
            MediaCategory("2", "SÉRIES TURQUES", MediaType.Series),
            MediaCategory("3", "FILMS COMÉDIE", MediaType.Movie),
        )
        val plan = AiSearchPlan(null, listOf("comedie"), emptyList(), emptyList(), emptyList(), null, null, SearchSort.Rating, "")
        assertEquals(listOf("3"), matchingCategories(plan, categories).map { it.id })
        val turkish = plan.copy(genres = emptyList(), regions = listOf("turc", "turque"), type = MediaType.Series)
        assertEquals(listOf("2"), matchingCategories(turkish, categories).map { it.id })
    }

    @Test
    fun regionWinsWhenGenreAndRegionNeverMeetInOneCategory() {
        val categories = listOf(
            MediaCategory("1", "SERIES ROMANCE", MediaType.Series),
            MediaCategory("2", "SERIES TURQUES", MediaType.Series),
        )
        val plan = AiSearchPlan(MediaType.Series, listOf("romance"), listOf("turques"), emptyList(), emptyList(), null, null, SearchSort.Rating, "")
        assertEquals(listOf("2"), matchingCategories(plan, categories).map { it.id })
    }

    @Test
    fun releaseYearIgnoresImplausibleYears() {
        assertEquals(1995, releaseYear("Heat (1995)", 2030))
        assertEquals(1968, releaseYear("2001 A Space Odyssey 1968", 2030))
        assertNull(releaseYear("Blade Runner 2049", 2030))
        assertNull(releaseYear("Sans année", 2030))
    }

    @Test
    fun yearFilterKeepsUndatedOnlyWhenFewDatedResults() {
        val entries = listOf(movie(1, "A (1994)"), movie(2, "B (2010)"), movie(3, "C"))
        val few = filterByYears(entries, 1990, 1999, enoughKnown = 8, maxYear = 2030)
        assertEquals(listOf(1, 3), few.map { it.id })
        val strict = filterByYears(entries, 1990, 1999, enoughKnown = 1, maxYear = 2030)
        assertEquals(listOf(1), strict.map { it.id })
    }

    @Test
    fun suggestedTitlesMustExistAsWholeWords() {
        val results = listOf(movie(1, "Heat (1995)"), movie(2, "Heater"), movie(3, "Die Hard 2"))
        assertEquals(listOf(1), pickTitleMatches("heat", results).map { it.id })
        assertEquals(listOf(3), pickTitleMatches("Die Hard 2", results).map { it.id })
    }

    @Test
    fun picksKeepOnlyKnownIdsWithoutDuplicates() {
        val picks = parsePicks("""{"picks":[{"id":"F1","why":"a"},{"id":"F1","why":"b"},{"id":"X9","why":"c"},{"id":"S2","why":"d"}]}""", setOf("F1", "S2"))!!
        assertEquals(listOf("F1", "S2"), picks.map { it.id })
        assertNull(parsePicks("""{"picks":[{"id":"Z","why":"x"}]}""", setOf("F1")))
    }

    @Test
    fun collectionsNeedThreeKnownItemsAndNeverRepeatAnItem() {
        val ids = setOf("F1", "F2", "F3", "F4", "F5")
        val answer = """{"collections":[{"title":"Saga","ordered":true,"ids":["F1","F2","F3","F9"]},{"title":"Trop petit","ids":["F3","F4"]}]}"""
        val result = parseCollections(answer, ids)!!
        assertEquals(1, result.size)
        assertTrue(result[0].ordered)
        assertEquals(listOf("F1", "F2", "F3"), result[0].ids)
    }

    @Test
    fun briefDropsUnknownReferences() {
        val brief = parseBrief("""{"headline":"Soirée foot","items":[{"ref":"M1","text":"PSG - OM"},{"ref":"M7","text":"inventé"}]}""", setOf("M1"))!!
        assertEquals(1, brief.items.size)
        assertEquals("Soirée foot", brief.headline)
    }

    @Test
    fun ficheSectionsAreSplitByMarkerInAnyOrder() {
        val answer = "ORDRE: [2,1,3]\nAVIS: {\"audience\":\"Adultes\",\"mood\":\"Sombre\",\"caution\":\"Violence\"}\nTRADUCTION:\nUn détective revient."
        val sections = parseFicheSections(answer, 3)
        assertEquals(listOf(1, 0, 2), sections.order)
        assertEquals("Sombre", sections.review?.mood)
        assertEquals("Un détective revient.", sections.translation)
    }

    @Test
    fun reviewRoundTripsThroughItsCacheEncoding() {
        val review = AiReview("Famille", "Léger", "Rien de particulier")
        assertEquals(review, parseReview(review.encode()))
        assertNull(parseReview("{}"))
    }

    @Test
    fun remoteIntentRequiresAQueryWhenTheActionTargetsSomething() {
        assertEquals(RemoteAction.WatchChannel, parseRemoteIntent("""{"action":"watch_channel","query":"TF1","reply":"ok"}""")?.action)
        assertNull(parseRemoteIntent("""{"action":"watch_channel","query":"","reply":"ok"}"""))
        assertNotNull(parseRemoteIntent("""{"action":"resume","query":"","reply":"ok"}"""))
        assertNull(parseRemoteIntent("""{"action":"format_disk","query":"x"}"""))
    }

    @Test
    fun bilingualCueKeepsTranslationThenItalicOriginal() {
        assertEquals(listOf("Bonjour", "<i>Hello there</i>"), bilingualLines(listOf("Bonjour"), listOf("<i>Hello</i>", "there")))
        assertEquals(listOf("Same"), bilingualLines(listOf("Same"), listOf("Same")))
    }

    @Test
    fun glossaryLineIsParsedAndBadPairsIgnored() {
        val glossary = parseGlossary("1|Salut\n#GLOSSAIRE: Ali=علي; Omar = عمر; mauvais; X=X")
        assertEquals(mapOf("Ali" to "علي", "Omar" to "عمر"), glossary)
        assertTrue(parseGlossary("1|Salut").isEmpty())
    }

    @Test
    fun claudeSystemPromptIsCachedOnlyWhenLong() {
        assertTrue(AiChatClient.messagesSystem(AiProvider.Claude, "court") is String)
        val long = AiChatClient.messagesSystem(AiProvider.Claude, "x".repeat(AiChatClient.PROMPT_CACHE_MIN_CHARS))
        assertFalse(long is String)
        assertTrue(AiChatClient.messagesSystem(AiProvider.OpenCode, "x".repeat(5_000)) is String)
    }

    @Test
    fun allowedEntriesHideLockedCategoriesUntilUnlocked() {
        val categories = listOf(MediaCategory("9", "ADULTE", MediaType.Movie))
        val library = UserLibrarySnapshot(lockedCategories = setOf("Movie:9"))
        val locked = movie(1, "X", "9")
        assertFalse(allowedEntries(categories, library, parentalControlEnabled = true, parentalUnlocked = false)(locked))
        assertTrue(allowedEntries(categories, library, parentalControlEnabled = true, parentalUnlocked = true)(locked))
        assertTrue(allowedEntries(categories, library, parentalControlEnabled = false, parentalUnlocked = false)(locked))
    }
}
