package app.winters.octo.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val InputShape = RoundedCornerShape(12.dp)

// A text field sunk into the surface: dark inner shadow from the top left,
// a faint highlight from the bottom right, both deeper while typing. Its
// accent fill strengthens from a tenth to 15% while typing, and a thin
// accent ring fades in round it.
@Composable
fun GlassInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    contentType: ContentType? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val depth by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = octoTween(motionScale(), OctoDuration.Card, OctoEasing.Overshoot),
        label = "input depth",
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = OctoType.body.copy(color = OctoColors.TextPrimary),
        cursorBrush = SolidColor(OctoColors.Accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .then(if (contentType != null) Modifier.semantics { this.contentType = contentType } else Modifier),
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusRing(InputShape, { depth.coerceIn(0f, 1f) }, width = 1.5.dp, color = OctoColors.Accent.copy(alpha = 0.60f))
                    .clip(InputShape)
                    .drawBehind { drawRect(OctoColors.Accent.copy(alpha = 0.10f + 0.05f * depth.coerceIn(0f, 1f))) }
                    // The stylesheet uses a negative spread here, which Android
                    // ignores; a slightly smaller blur reaches the same depth.
                    .innerShadow(InputShape) {
                        val reach = 4f + depth
                        radius = shadowBlur(7f + 2f * depth).toPx()
                        offset = Offset(reach.dp.toPx(), reach.dp.toPx())
                        color = Color.Black.copy(alpha = 0.5f + 0.1f * depth)
                    }
                    .innerShadow(InputShape) {
                        val reach = 4f + depth
                        radius = shadowBlur(7f + 2f * depth).toPx()
                        offset = Offset(-reach.dp.toPx(), -reach.dp.toPx())
                        color = Color.White.copy(alpha = 0.10f + 0.05f * depth)
                    }
                    .border(1.dp, OctoColors.Accent.copy(alpha = 0.10f), InputShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = OctoType.body, color = OctoColors.TextMuted, maxLines = 1)
                    }
                    field()
                }
                trailing?.invoke()
            }
        },
    )
}
