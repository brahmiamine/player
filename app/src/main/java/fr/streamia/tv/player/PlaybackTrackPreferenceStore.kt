package fr.streamia.tv.player

import android.content.Context

data class PlaybackTrackPreferences(val audioLanguage: String?, val subtitleLanguage: String?)

/** Choix globaux réappliqués à chaque chaîne, film ou épisode lorsqu'ils sont disponibles. */
class PlaybackTrackPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PlaybackTrackPreferences = PlaybackTrackPreferences(
        audioLanguage = preferences.getString(KEY_AUDIO, AUTO)?.takeUnless { it == AUTO },
        subtitleLanguage = preferences.getString(KEY_SUBTITLE, OFF)?.takeUnless { it == OFF },
    )

    fun saveAudio(language: String?) {
        preferences.edit().putString(KEY_AUDIO, language ?: AUTO).apply()
    }

    fun saveSubtitle(language: String?) {
        preferences.edit().putString(KEY_SUBTITLE, language ?: OFF).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "streamia-playback-tracks-v1"
        const val KEY_AUDIO = "audio_language"
        const val KEY_SUBTITLE = "subtitle_language"
        const val AUTO = "__auto__"
        const val OFF = "__off__"
    }
}
