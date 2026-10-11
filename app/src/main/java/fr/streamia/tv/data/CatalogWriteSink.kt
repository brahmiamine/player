package fr.streamia.tv.data

import fr.streamia.tv.domain.MediaCategory
import fr.streamia.tv.domain.MediaEntry

/**
 * Destination d'écriture en flux pour un catalogue fournisseur en cours de récupération. Permet à
 * [XtreamClient] d'écrire les catégories puis les entrées par lots au fil du parsing réseau, sans
 * jamais construire l'intégralité du catalogue (potentiellement des centaines de milliers de lignes)
 * comme une seule liste en mémoire avant de la persister. [CatalogDatabase.ReplaceSession] est
 * l'implémentation réelle ; l'interface existe pour que [XtreamClient] n'ait pas besoin de dépendre
 * des types SQLite.
 */
interface CatalogWriteSink {
    fun writeCategories(categories: List<MediaCategory>)
    fun writeEntries(entries: List<MediaEntry>)
}

/**
 * Écriture d'un catalogue section par section (Direct, puis Films, puis Séries) : chaque section a
 * sa propre transaction, validée dès qu'elle est complète pour que l'accueil puisse l'afficher
 * avant la suite. Une section sans entrée est annulée, rien n'est écrit.
 */
internal interface StagedCatalogWriter {
    /** Ouvre la transaction de [type] ; les catégories puis les entrées s'écrivent dans le sink renvoyé. */
    fun begin(type: fr.streamia.tv.domain.MediaType): CatalogWriteSink

    /** Valide la section ouverte : elle devient visible. [account] est réécrit avec le profil. */
    fun commit(account: fr.streamia.tv.domain.AccountInfo)

    /** Annule la section ouverte, s'il y en a une. */
    fun abort()
}
