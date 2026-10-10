package com.sakata.focusflow

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Draw inside the Card's shape clip, using its current bounds even during disclosure. */
internal data class CardPressIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        CardPressNode(interactionSource, color)
}

private class CardPressNode(
    private val interactionSource: InteractionSource,
    private val color: Color
) : Modifier.Node(), DrawModifierNode {
    private val active = mutableSetOf<Interaction>()
    private var highlight by mutableFloatStateOf(0f)
    private var fade: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press, is FocusInteraction.Focus, is HoverInteraction.Enter -> active.add(interaction)
                    is PressInteraction.Release -> active.remove(interaction.press)
                    is PressInteraction.Cancel -> active.remove(interaction.press)
                    is FocusInteraction.Unfocus -> active.remove(interaction.focus)
                    is HoverInteraction.Exit -> active.remove(interaction.enter)
                }
                fade?.cancel()
                if (active.isNotEmpty()) {
                    // Press feedback starts immediately; only release fades. No layout is invalidated.
                    highlight = 1f
                } else {
                    fade = coroutineScope.launch {
                        Animatable(highlight).animateTo(0f, MotionSpec.pulse()) {
                            highlight = value
                        }
                    }
                }
            }
        }
    }

    override fun onDetach() {
        active.clear()
        highlight = 0f
        fade = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (highlight > 0f) drawRect(color, alpha = 0.08f * highlight)
    }
}
