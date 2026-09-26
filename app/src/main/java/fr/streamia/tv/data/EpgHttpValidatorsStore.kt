package fr.streamia.tv.data

import android.content.Context

/**
 * ETag / Last-Modified du dernier guide XMLTV synchronisé, par liste et par source : la
 * synchronisation suivante demande au serveur si le guide a changé avant de le retélécharger.
 */
internal class EpgHttpValidatorsStore(context: Context) {
    private val preferences by lazy { context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    fun load(profileId: String, sourceUrl: String): HttpValidators? {
        val key = key(profileId, sourceUrl)
        val validators = HttpValidators(preferences.getString("$key|etag", null), preferences.getString("$key|modified", null))
        return validators.takeUnless { it.isEmpty }
    }

    fun save(profileId: String, sourceUrl: String, validators: HttpValidators) {
        val key = key(profileId, sourceUrl)
        preferences.edit()
            .putString("$key|etag", validators.etag)
            .putString("$key|modified", validators.lastModified)
            .apply()
    }

    // Condensat : l'URL du fournisseur contient les identifiants, jamais écrits en clair ici.
    private fun key(profileId: String, sourceUrl: String): String = "$profileId|${sourceUrl.hashCode()}"

    private companion object {
        const val PREFERENCES_NAME = "streamia-epg-http-validators"
    }
}
