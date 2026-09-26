package fr.streamia.tv.player

/**
 * Contrôle réel de l'image du Direct à partir des compteurs du décodeur vidéo, relevés une fois par
 * seconde : fps **réellement affichés**, part d'images perdues, et image figée (plus aucune image
 * affichée alors que le lecteur est en lecture). Aucune allocation par relevé.
 */
class VideoFrameMonitor(private val fpsWindowMs: Long = 3_000L) {
    private var lastRendered = -1
    private var lastAdvanceAtMs = 0L
    private var windowStartAtMs = -1L
    private var windowRendered = 0
    private var windowDropped = 0

    /** Images par seconde réellement affichées sur la dernière fenêtre, `null` avant la première mesure. */
    var realFps: Float? = null
        private set

    /** Part des images perdues (0–1) sur la dernière fenêtre. */
    var droppedRatio: Float? = null
        private set

    fun reset(nowMs: Long) {
        lastRendered = -1
        lastAdvanceAtMs = nowMs
        windowStartAtMs = -1L
        realFps = null
        droppedRatio = null
    }

    /**
     * Enregistre un relevé des compteurs ; renvoie depuis combien de temps aucune nouvelle image n'a
     * été affichée pendant la lecture (0 en pause ou en chargement : ce n'est pas une image figée).
     */
    fun sample(nowMs: Long, renderedFrames: Int, droppedFrames: Int, playing: Boolean): Long {
        // Compteurs recréés (nouveau décodeur) : on repart de ce relevé.
        if (renderedFrames < lastRendered) {
            lastRendered = -1
            windowStartAtMs = -1L
        }
        if (!playing) {
            lastRendered = renderedFrames
            lastAdvanceAtMs = nowMs
            windowStartAtMs = -1L
            return 0L
        }
        if (lastRendered < 0 || renderedFrames > lastRendered) lastAdvanceAtMs = nowMs
        lastRendered = renderedFrames

        if (windowStartAtMs < 0L) {
            windowStartAtMs = nowMs
            windowRendered = renderedFrames
            windowDropped = droppedFrames
        } else if (nowMs - windowStartAtMs >= fpsWindowMs) {
            val rendered = renderedFrames - windowRendered
            val dropped = (droppedFrames - windowDropped).coerceAtLeast(0)
            if (rendered > 0) {
                realFps = rendered * 1_000f / (nowMs - windowStartAtMs)
                droppedRatio = dropped.toFloat() / (rendered + dropped)
            }
            windowStartAtMs = nowMs
            windowRendered = renderedFrames
            windowDropped = droppedFrames
        }
        return nowMs - lastAdvanceAtMs
    }
}

enum class LiveAudioIssue(val label: String) {
    NoTrack("pas de son (aucune piste audio)"),
    Unsupported("son non pris en charge"),
    Errors("son en erreur"),
}

/**
 * Son du Direct : piste absente d'une chaîne qui a de l'image, piste présente mais qu'aucun
 * décodeur ne sait lire, ou erreurs audio répétées pendant la lecture.
 */
fun liveAudioIssue(
    hasVideoTrack: Boolean,
    audioTrackGroups: Int,
    anyAudioSelected: Boolean,
    audioErrors: Int,
): LiveAudioIssue? = when {
    audioTrackGroups == 0 -> if (hasVideoTrack) LiveAudioIssue.NoTrack else null
    !anyAudioSelected -> LiveAudioIssue.Unsupported
    audioErrors >= MAX_AUDIO_ERRORS -> LiveAudioIssue.Errors
    else -> null
}

private const val MAX_AUDIO_ERRORS = 3
