package com.videoplayer.ui.shared.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.videoplayer.util.DeviceHelper

/**
 * Reusable focus indicator modifier for remote navigation
 * Provides high-contrast visual feedback for TV viewing distances
 * Returns a modifier that can be applied to any composable
 */
@Composable
fun focusIndicatorModifier(
    isFocused: Boolean,
    borderWidth: Float = 4f,
    scaleAmount: Float = 1.02f,
    borderColor: Color = MaterialTheme.ruTvColors.gold,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
): Modifier {
    val isRemoteMode = DeviceHelper.isRemoteInputActive()

    // Only show focus indicator in remote mode
    if (!isRemoteMode || !isFocused) {
        return Modifier
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) scaleAmount else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "focus_scale"
    )

    val actualBorderWidth = borderWidth.dp

    return Modifier
        .scale(scale)
        .then(
            if (actualBorderWidth > 0.dp && isFocused) {
                Modifier.border(actualBorderWidth, borderColor, shape)
            } else {
                Modifier
            }
        )
}

