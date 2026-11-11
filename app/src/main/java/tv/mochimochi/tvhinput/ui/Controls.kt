package tv.mochimochi.tvhinput.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

private val FieldShape = RoundedCornerShape(12.dp)

/*
 * Shared focus style for the form: a focused control gets a lighter fill and a white
 * outline instead of the stock TV inverse (white) fill, which would wash out field text.
 */

@Composable
private fun rowColors() = MaterialTheme.colorScheme.let { cs ->
    ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = cs.onSurface,
        focusedContainerColor = cs.surfaceVariant,
        focusedContentColor = cs.onSurface,
        pressedContainerColor = cs.surfaceVariant,
        pressedContentColor = cs.onSurface,
    )
}

private fun stroke(width: Int, color: Color) = Border(BorderStroke(width.dp, color))

/**
 * Text field for D-pad use. Compose for TV has no text field, and a bare BasicTextField
 * would grab focus (and pop the keyboard) as the user moves through the form. Here the
 * field is a focusable surface; pressing OK starts editing, and Back, Up/Down or the
 * keyboard's action button stop it.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    password: Boolean = false,
    error: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun stopEditing(move: FocusDirection? = null) {
        editing = false
        focusRequester.requestFocus()
        move?.let(focusManager::moveFocus)
    }

    BackHandler(enabled = editing) { stopEditing() }
    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }

    val idleBorder = when {
        error != null -> cs.error
        editing -> cs.primary
        else -> cs.border
    }
    Column(modifier) {
        Surface(
            onClick = { editing = true },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            shape = ClickableSurfaceDefaults.shape(FieldShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = rowColors(),
            border = ClickableSurfaceDefaults.border(
                border = stroke(if (editing) 2 else 1, idleBorder),
                focusedBorder = stroke(2, if (error != null) cs.error else cs.onSurface),
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (editing) cs.primary else cs.onSurfaceVariant,
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { stopEditing(FocusDirection.Down) },
                        onDone = { stopEditing() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .focusRequester(fieldFocus)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { if (!it.isFocused && editing) editing = false }
                        .onPreviewKeyEvent { e ->
                            val dir = when (e.key) {
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionDown -> FocusDirection.Down
                                else -> return@onPreviewKeyEvent false
                            }
                            if (e.type == KeyEventType.KeyDown) stopEditing(dir)
                            true
                        },
                )
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelMedium,
                color = cs.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

/** A full-width row that toggles a switch, the TV-friendly form of a labelled switch. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(FieldShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = rowColors(),
        border = ClickableSurfaceDefaults.border(
            border = stroke(0, Color.Transparent),
            focusedBorder = stroke(2, cs.onSurface),
        ),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            // Not focusable on its own: the whole row is the control.
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Segmented single choice, e.g. HTTP | HTSP. */
@Composable
fun SegmentedChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEachIndexed { i, label ->
            Surface(
                selected = i == selected,
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                shape = SelectableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = SelectableSurfaceDefaults.scale(focusedScale = 1.03f),
                colors = SelectableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = cs.onSurface,
                    focusedContainerColor = cs.surfaceVariant,
                    focusedContentColor = cs.onSurface,
                    selectedContainerColor = cs.secondaryContainer,
                    selectedContentColor = cs.onSecondaryContainer,
                    focusedSelectedContainerColor = cs.secondaryContainer,
                    focusedSelectedContentColor = cs.onSecondaryContainer,
                ),
                border = SelectableSurfaceDefaults.border(
                    border = stroke(1, cs.border),
                    focusedBorder = stroke(2, cs.onSurface),
                    selectedBorder = stroke(2, cs.primary),
                    focusedSelectedBorder = stroke(2, cs.onSurface),
                ),
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Indeterminate linear progress bar; tv-material has no progress indicators. */
@Composable
fun IndeterminateProgressBar(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val x by rememberInfiniteTransition(label = "progress").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "progressX",
    )
    Canvas(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
        drawRect(cs.borderVariant)
        val w = size.width * 0.35f
        drawRect(cs.primary, topLeft = Offset((size.width + w) * x - w, 0f), size = Size(w, size.height))
    }
}
