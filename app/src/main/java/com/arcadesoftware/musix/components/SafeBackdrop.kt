package com.arcadesoftware.musix.components

import androidx.compose.ui.Modifier
import com.kyant.backdrop.drawBackdrop as originalDrawBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as originalLayerBackdrop

fun Modifier.drawBackdrop(
    backdrop: com.kyant.backdrop.Backdrop,
    shape: () -> androidx.compose.ui.graphics.Shape,
    effects: com.kyant.backdrop.BackdropEffectScope.() -> Unit = {},
    highlight: (() -> com.kyant.backdrop.highlight.Highlight?)? = null,
    shadow: (() -> com.kyant.backdrop.shadow.Shadow?)? = null,
    innerShadow: (() -> com.kyant.backdrop.shadow.InnerShadow?)? = null,
    layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = null,
    onDrawSurface: (androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit)? = null
): Modifier {
    return this.then(
        originalDrawBackdrop(
            backdrop = backdrop,
            shape = shape,
            effects = effects,
            highlight = highlight ?: { null },
            shadow = shadow ?: { null },
            innerShadow = innerShadow ?: { null },
            layerBlock = layerBlock,
            onDrawSurface = onDrawSurface ?: {}
        )
    )
}

fun Modifier.layerBackdrop(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
): Modifier {
    return this.then(originalLayerBackdrop(backdrop))
}
