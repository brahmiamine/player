package fr.streamia.tv.liveonsat

import fr.streamia.tv.domain.MediaEntry

/** Un diffuseur listé par liveonsat.com pour un match, tel quel (nom brut du site). */
data class LiveOnSatChannel(
    val name: String,
    val free: Boolean,
)

data class LiveOnSatMatch(
    val competition: String,
    val participantA: String,
    val participantB: String,
    val participantALogoUrl: String? = null,
    val participantBLogoUrl: String? = null,
    val startEpochSeconds: Long,
    val channels: List<LiveOnSatChannel>,
)

/**
 * Un match liveonsat.com avec, pour chaque diffuseur reconnu, la ou les chaînes correspondantes du
 * profil courant — plusieurs quand le diffuseur existe en plusieurs résolutions, ou quand il
 * désigne un bouquet entier (ex. "beIN Connect MENA") plutôt qu'une chaîne précise. Les diffuseurs
 * absents de [matchedChannels] restent dans [LiveOnSatMatch.channels] : l'UI les affiche quand
 * même, simplement non cliquables.
 */
data class ResolvedLiveOnSatMatch(
    val match: LiveOnSatMatch,
    val matchedChannels: Map<String, List<MediaEntry>>,
    val epgStartEpochSeconds: Long? = null,
    val epgEndEpochSeconds: Long? = null,
)
