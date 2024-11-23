package it.fast4x.rimusic.enums

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import it.fast4x.rimusic.R

enum class MusicAnimationType {
    Bars,
    CrazyBars,
    CrazyPoints,
    Bubbles;

    val textName: String
        @Composable
        get() = when(this) {
            Bars -> stringResource(R.string.music_animations_bars)
            CrazyBars -> stringResource(R.string.music_animations_crazy_bars)
            CrazyPoints -> stringResource(R.string.music_animations_crazy_points)
            Bubbles -> stringResource(R.string.music_animations_bubbles)
        }
}