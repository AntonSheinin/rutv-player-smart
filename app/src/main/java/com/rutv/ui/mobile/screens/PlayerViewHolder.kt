package com.rutv.ui.mobile.screens

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.ui.PlayerView
import com.rutv.R

/**
 * Holds onto a single PlayerView instance so we don't inflate/destroy it on every recomposition.
 * Handles safely detaching from an old parent before handing it to Compose's AndroidView.
 */
class PlayerViewHolder(private val view: PlayerView) {
    fun obtain(): PlayerView {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }
}

@Composable
fun rememberPlayerViewHolder(): PlayerViewHolder {
    val context = LocalContext.current
    return remember {
        val playerView = LayoutInflater.from(context)
            .inflate(R.layout.player_view_texture, null, false) as PlayerView
        PlayerViewHolder(playerView)
    }
}
