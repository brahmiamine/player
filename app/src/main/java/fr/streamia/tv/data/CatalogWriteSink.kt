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
