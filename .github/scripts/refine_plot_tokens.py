from pathlib import Path

path = Path("app/src/main/java/fr/streamia/tv/recommendation/ContentSimilarity.kt")
text = path.read_text()

text = text.replace(
    "        val sourcePlotTokens = tokens(source.plot)\n        val candidatePlotTokens = tokens(candidate.plot)",
    "        val sourcePlotTokens = plotTokens(source.plot)\n        val candidatePlotTokens = plotTokens(candidate.plot)",
    1,
)

needle = '''    private fun personTokens(value: String?): Set<String> = rawTokens(value, minLength = 2)
        .filterNotTo(linkedSetOf()) { it in PERSON_NOISE_TOKENS }

    private fun tokens(value: String?): Set<String> = rawTokens(value, minLength = 3)
'''
replacement = '''    private fun personTokens(value: String?): Set<String> = rawTokens(value, minLength = 2)
        .filterNotTo(linkedSetOf()) { it in PERSON_NOISE_TOKENS }

    /**
     * Canonicalisation très légère des mots d'intrigue. Elle rapproche les variantes françaises
     * et anglaises les plus fréquentes sans modèle lourd ni stemming agressif sur Android TV.
     */
    private fun plotTokens(value: String?): Set<String> = rawTokens(value, minLength = 3)
        .filterNot { it in STOP_WORDS || it in TITLE_NOISE_TOKENS }
        .mapTo(linkedSetOf()) { token -> PLOT_TOKEN_ALIASES[token] ?: token }

    private fun tokens(value: String?): Set<String> = rawTokens(value, minLength = 3)
'''
if needle not in text:
    raise SystemExit("plot token function marker not found")
text = text.replace(needle, replacement, 1)

needle = '''        val PERSON_NOISE_TOKENS = setOf("and", "avec", "with", "et")

        val GENRE_ALIASES = mapOf(
'''
replacement = '''        val PERSON_NOISE_TOKENS = setOf("and", "avec", "with", "et")

        val PLOT_TOKEN_ALIASES = mapOf(
            "survivre" to "survival",
            "survit" to "survival",
            "survivent" to "survival",
            "survivant" to "survival",
            "survivants" to "survival",
            "survive" to "survival",
            "survives" to "survival",
            "surviving" to "survival",
            "ennemi" to "enemy",
            "ennemis" to "enemy",
            "ennemie" to "enemy",
            "ennemies" to "enemy",
            "enemies" to "enemy",
            "soldat" to "military",
            "soldats" to "military",
            "militaire" to "military",
            "militaires" to "military",
            "soldier" to "military",
            "soldiers" to "military",
            "army" to "military",
            "armee" to "military",
        )

        val GENRE_ALIASES = mapOf(
'''
if needle not in text:
    raise SystemExit("plot alias marker not found")
text = text.replace(needle, replacement, 1)
path.write_text(text)
