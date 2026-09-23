package com.pira.ccloud.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val FocusOutlineWidth = 2.dp

/**
 * Outlines this element while it has D-pad focus, so TV remote users can see where they are
 * (the default ripple focus highlight is too faint on a TV). Put it before the element's
 * clickable, or in the modifier of a component such as IconButton, so it observes that focus.
 *
 * [outset] draws the outline outside the element, for small targets like color swatches whose
 * own color could hide an outline drawn on top of them.
 */
fun Modifier.focusRing(
    shape: Shape,
    color: Color = Color.Unspecified,
    outset: Dp = 0.dp
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    onFocusChanged { isFocused = it.isFocused }.focusOutline(isFocused, shape, color, outset)
}

/**
 * Draws the [focusRing] outline while [isFocused] is true, for when the focused element is a
 * child of this one, e.g. a card whose header is the clickable part.
 */
fun Modifier.focusOutline(
    isFocused: Boolean,
    shape: Shape,
    color: Color = Color.Unspecified,
    outset: Dp = 0.dp
): Modifier = composed {
    val outlineColor = color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    drawWithContent {
        drawContent()
        if (isFocused) {
            val strokeWidth = FocusOutlineWidth.toPx()
            // The stroke is centered on the outline, so offset it to put the stroke's outer edge
            // at `outset` outside the bounds (with no outset the whole stroke is inside)
            val offset = outset.toPx() - strokeWidth / 2
            val outline = shape.createOutline(
                Size(size.width + offset * 2, size.height + offset * 2),
                layoutDirection,
                this
            )
            translate(-offset, -offset) {
                drawOutline(outline, outlineColor, style = Stroke(strokeWidth))
            }
        }
    }
}
