package dev.tvhinput.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.tvhinput.R
import dev.tvhinput.StreamProtocol
import dev.tvhinput.TvhConfig

enum class StatusKind { WORKING, DONE, ERROR }

data class SetupStatus(val text: String, val kind: StatusKind)

/** Form state, seeded from the saved config. */
class SetupState(saved: TvhConfig?) {
    var host by mutableStateOf(saved?.host.orEmpty())
    var port by mutableStateOf((saved?.port ?: 9981).toString())
    var https by mutableStateOf(saved?.https ?: false)
    var webRoot by mutableStateOf(saved?.webRoot.orEmpty())
    var username by mutableStateOf(saved?.username.orEmpty())
    var password by mutableStateOf(saved?.password.orEmpty())
    var profile by mutableStateOf(saved?.profile ?: "pass")
    var protocol by mutableStateOf(saved?.protocol ?: StreamProtocol.HTTP)
    var htspPort by mutableStateOf((saved?.htspPort ?: 9982).toString())
    var prewarm by mutableStateOf(saved?.prewarm ?: false)
    var hostError by mutableStateOf(false)
    var status by mutableStateOf<SetupStatus?>(null)
    var syncing by mutableStateOf(false)
}

/** Keeps [margin] px between the focused field and the edge of the scrolling pane. */
@OptIn(ExperimentalFoundationApi::class)
private class MarginBringIntoViewSpec(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset - margin
        val trailing = offset + size + margin - containerSize
        return when {
            leading < 0 -> leading
            trailing > 0 -> if (size + 2 * margin > containerSize) leading else trailing
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(state: SetupState, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hostFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { hostFocus.requestFocus() }
    LaunchedEffect(state.hostError) { if (state.hostError) hostFocus.requestFocus() }

    // 48dp/27dp insets keep everything inside the TV overscan-safe area.
    Row(Modifier.fillMaxSize().background(cs.surface).padding(horizontal = 48.dp)) {
        Column(
            Modifier.weight(2f).fillMaxHeight().padding(top = 27.dp, bottom = 27.dp, end = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            AnimatedVisibility(visible = state.status != null) {
                state.status?.let { StatusCard(it, Modifier.padding(top = 32.dp)) }
            }
        }

        val margin = with(LocalDensity.current) { 48.dp.toPx() }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(margin) { MarginBringIntoViewSpec(margin) }) {
            Column(
                Modifier.weight(3f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 27.dp),
            ) {
                val fieldGap = Modifier.padding(top = 12.dp)
                SectionHeader(stringResource(R.string.section_server), first = true)
                Row(fieldGap) {
                    TvTextField(
                        value = state.host,
                        onValueChange = { state.host = it; state.hostError = false },
                        label = stringResource(R.string.host),
                        keyboardType = KeyboardType.Uri,
                        error = if (state.hostError) stringResource(R.string.host_required) else null,
                        focusRequester = hostFocus,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    TvTextField(
                        value = state.port,
                        onValueChange = { state.port = it },
                        label = stringResource(R.string.port),
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.width(128.dp),
                    )
                }
                TvTextField(
                    value = state.webRoot,
                    onValueChange = { state.webRoot = it },
                    label = stringResource(R.string.web_root),
                    keyboardType = KeyboardType.Uri,
                    modifier = fieldGap,
                )
                SwitchRow(
                    label = stringResource(R.string.https),
                    checked = state.https,
                    onCheckedChange = { state.https = it },
                    modifier = fieldGap,
                )

                SectionHeader(stringResource(R.string.section_account))
                Row(fieldGap) {
                    TvTextField(
                        value = state.username,
                        onValueChange = { state.username = it },
                        label = stringResource(R.string.username),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    TvTextField(
                        value = state.password,
                        onValueChange = { state.password = it },
                        label = stringResource(R.string.password),
                        password = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionHeader(stringResource(R.string.section_streaming))
                val htsp = state.protocol == StreamProtocol.HTSP
                SegmentedChoice(
                    options = listOf(stringResource(R.string.protocol_http), stringResource(R.string.protocol_htsp)),
                    selected = if (htsp) 1 else 0,
                    onSelect = { state.protocol = if (it == 1) StreamProtocol.HTSP else StreamProtocol.HTTP },
                    modifier = fieldGap,
                )
                Text(
                    text = stringResource(if (htsp) R.string.protocol_htsp_hint else R.string.protocol_http_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                )
                // The streaming profile only applies to HTTP and the HTSP port only to HTSP.
                if (htsp) {
                    TvTextField(
                        value = state.htspPort,
                        onValueChange = { state.htspPort = it },
                        label = stringResource(R.string.htsp_port),
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                        modifier = fieldGap,
                    )
                    SwitchRow(
                        label = stringResource(R.string.prewarm),
                        checked = state.prewarm,
                        onCheckedChange = { state.prewarm = it },
                        modifier = fieldGap,
                    )
                    Text(
                        text = stringResource(R.string.prewarm_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    )
                } else {
                    TvTextField(
                        value = state.profile,
                        onValueChange = { state.profile = it },
                        label = stringResource(R.string.profile),
                        imeAction = ImeAction.Done,
                        modifier = fieldGap,
                    )
                }

                Button(
                    onClick = onConnect,
                    modifier = Modifier.padding(top = 28.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, first: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (first) 0.dp else 28.dp),
    )
}

@Composable
private fun StatusCard(status: SetupStatus, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (status.kind) {
        StatusKind.ERROR -> cs.errorContainer to cs.onErrorContainer
        StatusKind.DONE -> cs.primaryContainer to cs.onPrimaryContainer
        StatusKind.WORKING -> cs.surfaceVariant to cs.onSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = bg, contentColor = fg),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (status.kind == StatusKind.WORKING) {
                IndeterminateProgressBar(Modifier.padding(bottom = 12.dp))
            }
            Text(status.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
