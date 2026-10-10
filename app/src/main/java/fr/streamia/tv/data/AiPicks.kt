package fr.streamia.tv.data

/**
 * Propositions de l'assistant : « Ce soir ? », collections automatiques, explications des recommandations et
 * « Quoi de neuf maintenant ? ». Toutes suivent le même principe, pour limiter les appels et les inventions :
 * le programme prépare localement une liste courte de candidats réels (identifiant, titre), le modèle ne
 * répond qu'avec des identifiants de cette liste, et toute réponse qui en sort est écartée.
 */

/** Un élément proposé au modèle : [id] court (« F3 », « S2 », « T1 », « M4 ») et [label] une ligne de texte. */
data class AiCandidate(val id: String, val label: String)

enum class TonightMood(val label: String, val hint: String, val genres: List<String>) {
    Relax("Détente", "léger, rassurant, qui fait sourire", listOf("comedie", "comedy", "famille", "family", "animation")),
    Action("Action", "rythmé, plein d'adrénaline", listOf("action", "aventure", "adventure", "guerre", "war")),
    Emotion("Émotion", "touchant, humain", listOf("drame", "drama", "romance", "romantique")),
    Fear("Frissons", "suspense, thriller ou horreur", listOf("thriller", "horreur", "horror", "suspense", "policier", "crime")),
    Discover("Découverte", "original, hors des sentiers battus", emptyList()),
}

enum class TonightLength(val label: String, val hint: String) {
    Short("Court", "moins de 1 h 30"),
    Film("Un film", "environ 2 heures"),
    Series("Une série", "quelques épisodes"),
    Any("Peu importe", "aucune contrainte de durée"),
}

enum class TonightCompany(val label: String, val hint: String) {
    Alone("Seul", "pour une personne"),
    Couple("À deux", "en couple"),
    Family("En famille", "avec des enfants : uniquement du tout public"),
    Friends("Entre amis", "à plusieurs, qui se partage"),
}

data class TonightAnswers(val mood: TonightMood, val length: TonightLength, val company: TonightCompany)

/** Proposition retenue : l'[id] du candidat et la raison en une phrase. */
data class AiPick(val id: String, val why: String)

/** Collection proposée : [ordered] vrai pour une saga (à voir dans l'ordre). */
data class AiCollection(val title: String, val ordered: Boolean, val ids: List<String>)

/** Résumé de « Quoi de neuf ? » : une phrase d'accroche et quelques éléments qui renvoient à un candidat par [AiBriefItem.ref]. */
data class AiBrief(val headline: String, val items: List<AiBriefItem>)

data class AiBriefItem(val ref: String, val text: String)

private fun List<AiCandidate>.asLines(): String = joinToString("\n") { "${it.id}|${it.label}" }

internal fun tonightSystemPrompt(languageName: String, pool: List<AiCandidate>): String =
    "Tu es le programmateur d'une application TV. Choisis 5 propositions pour CE SOIR dans la liste ci-dessous, avec uniquement les identifiants de cette liste.\n" +
        "Réponds uniquement par un objet JSON : {\"picks\":[{\"id\":\"F3\",\"why\":\"…\"}]}\n" +
        "- why : 12 mots maximum, en $languageName, lien précis avec l'humeur, la durée, la compagnie ou les goûts. N'invente rien sur l'histoire, les acteurs ou le réalisateur.\n" +
        "- Respecte la compagnie : avec des enfants, rien qui ne soit pas tout public.\n" +
        "- Si possible, mélange un programme TV de ce soir (identifiants T…), un film (F…) et une série (S…) quand ils correspondent.\n" +
        "Liste (identifiant|titre|infos) :\n" + pool.asLines()

internal fun tonightUserPrompt(answers: TonightAnswers, tastes: List<String>): String =
    "Humeur : ${answers.mood.label} (${answers.mood.hint}). Durée : ${answers.length.label} (${answers.length.hint}). " +
        "Compagnie : ${answers.company.label} (${answers.company.hint})." +
        if (tastes.isEmpty()) "" else "\nDéjà aimé : " + tastes.joinToString("; ")

/** Propositions valides (identifiant connu, sans doublon), au plus [max]. Null si la réponse n'est pas exploitable. */
internal fun parsePicks(answer: String, validIds: Set<String>, max: Int = 5): List<AiPick>? {
    val picks = extractJsonObject(answer)?.optJSONArray("picks") ?: return null
    val result = LinkedHashMap<String, AiPick>()
    for (index in 0 until picks.length()) {
        val item = picks.optJSONObject(index) ?: continue
        val id = item.optString("id").trim()
        if (id in validIds && id !in result) result[id] = AiPick(id, shorten(item.optString("why"), 110))
        if (result.size >= max) break
    }
    return result.values.toList().takeIf { it.isNotEmpty() }
}

internal fun collectionsSystemPrompt(languageName: String, pool: List<AiCandidate>): String =
    "Tu regroupes des films et séries d'un catalogue en collections : sagas ou thèmes précis.\n" +
        "Réponds uniquement par un objet JSON : {\"collections\":[{\"title\":\"…\",\"ordered\":true,\"ids\":[\"F1\",\"F4\"]}]}\n" +
        "- 3 à 5 collections de 3 à 10 éléments, avec uniquement les identifiants de la liste, sans qu'un élément figure dans deux collections.\n" +
        "- Une saga (même franchise) : ordered=true, du premier au dernier épisode de la saga. Un thème : ordered=false, le plus pertinent d'abord.\n" +
        "- title : 5 mots maximum, en $languageName, précis (« Soirée thriller psychologique », « Saga Jason Bourne »), jamais un simple genre.\n" +
        "- Ne regroupe que ce qui va vraiment ensemble ; mieux vaut moins de collections que des collections forcées.\n" +
        "Liste (identifiant|titre|année) :\n" + pool.asLines()

internal const val COLLECTIONS_USER_PROMPT = "Propose les collections."

internal fun parseCollections(answer: String, validIds: Set<String>): List<AiCollection>? {
    val array = extractJsonObject(answer)?.optJSONArray("collections") ?: return null
    val used = HashSet<String>()
    val result = ArrayList<AiCollection>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val ids = item.stringList("ids", 12).filter { it in validIds && used.add(it) }
        val title = shorten(item.optString("title"), 60)
        if (title.isNotEmpty() && ids.size >= MIN_COLLECTION_SIZE) result += AiCollection(title, item.optBoolean("ordered", false), ids.take(10))
        if (result.size >= 5) break
    }
    return result.takeIf { it.isNotEmpty() }
}

private const val MIN_COLLECTION_SIZE = 3

internal fun explainSystemPrompt(languageName: String): String =
    "Tu expliques à un spectateur pourquoi des films et séries lui sont recommandés, d'après ses goûts.\n" +
        "Réponds uniquement par un objet JSON : {\"reasons\":{\"F1\":\"…\"}} avec les identifiants de la liste.\n" +
        "- Chaque raison : 12 mots maximum, en $languageName, un lien réel avec les goûts (genre, ambiance, saga, même univers).\n" +
        "- Ne cite un réalisateur ou un acteur commun que si tu en es certain. N'invente rien sur l'histoire.\n" +
        "- S'il n'y a aucun lien clair pour un identifiant, ne le mets pas."

internal fun explainUserPrompt(tastes: List<String>, items: List<AiCandidate>): String =
    "Goûts du spectateur : " + tastes.joinToString("; ") + "\nRecommandations (identifiant|titre) :\n" + items.asLines()

internal fun parseReasons(answer: String, validIds: Set<String>): Map<String, String>? {
    val reasons = extractJsonObject(answer)?.optJSONObject("reasons") ?: return null
    val result = LinkedHashMap<String, String>()
    reasons.keys().forEach { id ->
        if (id in validIds) shorten(reasons.optString(id), 110).takeIf(String::isNotEmpty)?.let { result[id] = it }
    }
    return result.takeIf { it.isNotEmpty() }
}

internal fun briefSystemPrompt(languageName: String, nowLabel: String, lines: List<AiCandidate>): String =
    "Tu résumes ce qui passe à la télévision maintenant (il est $nowLabel), uniquement à partir de la liste ci-dessous : " +
        "aucun score, aucun fait ni aucune chaîne qui n'y figure pas.\n" +
        "Réponds uniquement par un objet JSON : {\"headline\":\"…\",\"items\":[{\"ref\":\"M2\",\"text\":\"…\"}]}\n" +
        "- headline : une phrase de 20 mots maximum, en $languageName.\n" +
        "- items : 3 à 6 éléments parmi les plus intéressants (grands matchs, derbies, finales, événements, sinon programmes marquants). " +
        "ref est l'identifiant de la liste ; text : 14 mots maximum, en $languageName, avec la chaîne si la liste la donne.\n" +
        "Liste (identifiant|texte) :\n" + lines.asLines()

internal const val BRIEF_USER_PROMPT = "Quoi de neuf maintenant ?"

internal fun parseBrief(answer: String, validRefs: Set<String>): AiBrief? {
    val json = extractJsonObject(answer) ?: return null
    val array = json.optJSONArray("items") ?: return null
    val seen = HashSet<String>()
    val items = ArrayList<AiBriefItem>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val ref = item.optString("ref").trim()
        val text = shorten(item.optString("text"), 140)
        if (ref in validRefs && text.isNotEmpty() && seen.add(ref)) items += AiBriefItem(ref, text)
        if (items.size >= 6) break
    }
    if (items.isEmpty()) return null
    return AiBrief(shorten(json.optString("headline"), 160), items)
}
