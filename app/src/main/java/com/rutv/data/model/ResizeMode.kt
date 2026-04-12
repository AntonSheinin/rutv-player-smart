package com.rutv.data.model

/**
 * Domain-level representation of video resize/aspect ratio modes.
 * Maps to Media3's AspectRatioFrameLayout constants at the UI boundary.
 */
enum class ResizeMode(val intValue: Int) {
    FIT(0),
    FILL(3),
    ZOOM(4);

    fun next(): ResizeMode = when (this) {
        FIT -> FILL
        FILL -> ZOOM
        ZOOM -> FIT
    }

    companion object {
        fun fromInt(value: Int): ResizeMode =
            entries.firstOrNull { it.intValue == value } ?: FIT
    }
}
