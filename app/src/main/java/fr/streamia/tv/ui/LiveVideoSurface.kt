package fr.streamia.tv.ui

import androidx.compose.ui.Modifier
import androidx.media3.ui.AspectRatioFrameLayout

data class LiveVideoSurfacePlacement(
    val modifier: Modifier,
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
)
