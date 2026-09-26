package fr.streamia.tv.domain

/**
 * Règles du « Secours automatique » du Direct (Paramètres › Lecture & direct) :
 * - image coupée (ou chaîne qui ne démarre pas) pendant [SWITCH_AFTER_OUTAGE_MS] → autre version ;
 * - [CUTS_TO_SWITCH] coupures d'au moins [COUNTED_CUT_MIN_MS] en [CUT_WINDOW_MS] → autre version ;
 * - le compteur de coupures repart de zéro sur chaque nouvelle version ;
 * - sans autre version de la même langue, ni compteur ni bascule.
 */
object LiveFailoverRules {
    const val SWITCH_AFTER_OUTAGE_MS = 6_000L
    const val COUNTED_CUT_MIN_MS = 5_000L
    const val CUTS_TO_SWITCH = 3
    const val CUT_WINDOW_MS = 5 * 60_000L

    /** Une série de bascules se termine après 5 min sans nouvelle bascule : tout peut être réessayé. */
    const val CHAIN_RESET_AFTER_MS = 5 * 60_000L
}

/** Coupures de la version en cours sur une fenêtre glissante (une instance par version). */
class LiveCutCounter {
    private val cutEnds = ArrayDeque<Long>()

    val count: Int get() = cutEnds.size

    /** Enregistre une coupure terminée ; vrai quand elle atteint le seuil de bascule. */
    fun onCutEnded(durationMs: Long, endedAtMs: Long): Boolean {
        while (cutEnds.isNotEmpty() && endedAtMs - cutEnds.first() > LiveFailoverRules.CUT_WINDOW_MS) cutEnds.removeFirst()
        if (durationMs < LiveFailoverRules.COUNTED_CUT_MIN_MS) return false
        cutEnds.addLast(endedAtMs)
        return cutEnds.size >= LiveFailoverRules.CUTS_TO_SWITCH
    }
}

/** Versions déjà essayées depuis [origin], la chaîne de départ de la série de bascules. */
data class LiveFailoverChain(
    val origin: MediaEntry,
    val tried: Set<String>,
    val lastSwitchAtMs: Long,
    /** Toutes les versions ont été essayées : plus de bascule jusqu'à la fin de la série. */
    val exhausted: Boolean = false,
)

data class LiveFailoverDecision(
    /** Version à lancer, `null` pour rester sur la version en cours (relances habituelles). */
    val target: MediaEntry?,
    val chain: LiveFailoverChain,
    /** Retour sur la chaîne de départ faute d'autre version qui fonctionne. */
    val backToOrigin: Boolean = false,
    /** Première fois que la série est épuisée : à signaler une seule fois. */
    val justExhausted: Boolean = false,
)

/** Même langue/pays que la chaîne en cours ; une version sans préfixe convient à toutes. */
fun sameLiveLanguage(a: MediaEntry, b: MediaEntry): Boolean {
    val first = LiveVersionNames.language(a.displayName)
    val second = LiveVersionNames.language(b.displayName)
    return first == null || second == null || first == second
}

/** Il existe au moins une autre version, de la même langue, vers laquelle basculer. */
fun hasFailoverCandidates(current: MediaEntry, versions: List<MediaEntry>): Boolean =
    versions.any { it.key != current.key && sameLiveLanguage(current, it) }

/**
 * Prochaine bascule : la version la mieux classée ([rankLiveVersions]) de la même langue, pas encore
 * essayée dans cette série ; toutes essayées → retour sur la chaîne de départ, une seule fois.
 */
fun decideLiveFailover(
    current: MediaEntry,
    ranked: List<LiveVersionOption>,
    chain: LiveFailoverChain?,
    nowMs: Long,
): LiveFailoverDecision {
    val active = chain?.takeIf {
        current.key in it.tried && nowMs - it.lastSwitchAtMs < LiveFailoverRules.CHAIN_RESET_AFTER_MS
    } ?: LiveFailoverChain(origin = current, tried = setOf(current.key), lastSwitchAtMs = nowMs)
    if (active.exhausted) return LiveFailoverDecision(target = null, chain = active)

    val next = ranked.firstOrNull { it.sameLanguage && !it.current && it.entry.key !in active.tried }?.entry
    if (next != null) {
        return LiveFailoverDecision(
            target = next,
            chain = active.copy(tried = active.tried + next.key, lastSwitchAtMs = nowMs),
        )
    }
    val exhausted = active.copy(exhausted = true, lastSwitchAtMs = nowMs)
    return if (active.origin.key != current.key) {
        LiveFailoverDecision(target = active.origin, chain = exhausted, backToOrigin = true, justExhausted = true)
    } else {
        LiveFailoverDecision(target = null, chain = exhausted, justExhausted = true)
    }
}
