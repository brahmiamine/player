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
    val startEpochSeconds: Long,
    val channels: List<LiveOnSatChannel>,
)

/**
 * Un match liveonsat.com avec, pour chaque diffuseur reconnu, la chaîne correspondante du profil
 * courant. Les diffuseurs absents de [matchedChannels] restent dans [LiveOnSatMatch.channels] :
 * l'UI les affiche quand même, simplement non cliquables.
 */
data class ResolvedLiveOnSatMatch(
    val match: LiveOnSatMatch,
    val matchedChannels: Map<String, MediaEntry>,
)
