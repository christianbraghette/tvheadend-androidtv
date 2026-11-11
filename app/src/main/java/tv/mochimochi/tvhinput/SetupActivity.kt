package tv.mochimochi.tvhinput

import android.media.tv.TvInputInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import tv.mochimochi.tvhinput.ui.SetupScreen
import tv.mochimochi.tvhinput.ui.SetupState
import tv.mochimochi.tvhinput.ui.SetupStatus
import tv.mochimochi.tvhinput.ui.StatusKind
import tv.mochimochi.tvhinput.ui.TvhTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup screen. The TV app opens it when the user adds this input, and again from its channel
 * source settings to change the server or force a resync; Settings > Apps can open it too (it
 * has a plain launcher entry, not a TV home screen one: see the manifest).
 */
class SetupActivity : ComponentActivity() {
    private lateinit var state: SetupState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = SetupState(Settings.load(this))
        setContent {
            TvhTheme { SetupScreen(state, onConnect = ::connectAndSync) }
        }
    }

    private fun readForm(): TvhConfig? {
        var h = state.host.trim().substringAfter("://").substringBefore('/')
        var p = state.port.toIntOrNull()
        // Accept "host:port" typed into the host field.
        HOST_WITH_PORT.matchEntire(h)?.let { m ->
            h = m.groupValues[1]
            p = m.groupValues[2].toInt()
        }
        state.hostError = h.isEmpty()
        if (h.isEmpty()) return null
        return TvhConfig(
            host = h,
            port = p ?: 9981,
            https = state.https,
            webRoot = state.webRoot.trim(),
            username = state.username.trim(),
            password = state.password,
            profile = state.profile.trim(),
            protocol = state.protocol,
            htspPort = state.htspPort.toIntOrNull() ?: 9982,
            prewarm = state.prewarm,
        )
    }

    private fun showStatus(text: String, kind: StatusKind = StatusKind.WORKING) {
        state.status = SetupStatus(text, kind)
    }

    private fun connectAndSync() {
        if (state.syncing) return
        val config = readForm() ?: return
        state.syncing = true
        showStatus(getString(R.string.setup_connecting))
        lifecycleScope.launch {
            try {
                val client = TvhClient(config)
                val info = withContext(Dispatchers.IO) { client.serverInfo() }
                val sync = TvProviderSync(applicationContext, client)
                val snapshot = if (config.protocol == StreamProtocol.HTSP) {
                    showStatus(getString(R.string.setup_checking_htsp))
                    // No HTTP fallback: fail here, not at the first tune, if HTSP is unreachable or
                    // not allowed. The same connection brings the channels and guide.
                    withContext(Dispatchers.IO) { HtspGuide.fetch(client) }
                } else {
                    withContext(Dispatchers.IO) { sync.fetch() }
                }
                Settings.save(this@SetupActivity, config)

                showStatus(getString(R.string.setup_importing, info.name, info.version, config.protocol.name))
                val channels = withContext(Dispatchers.IO) { sync.syncChannels(snapshot) }

                showStatus(getString(R.string.setup_loading_guide, channels.size))
                val programs = withContext(Dispatchers.IO) { sync.syncPrograms(snapshot.events, channels) }
                SyncWorker.schedule(applicationContext)

                showStatus(getString(R.string.setup_done, channels.size, programs), StatusKind.DONE)
                setResult(RESULT_OK)
                // When opened from the system TV app's "add source" flow, return to it.
                if (intent.hasExtra(TvInputInfo.EXTRA_INPUT_ID)) finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus(getString(R.string.setup_error, e.message ?: e.javaClass.simpleName), StatusKind.ERROR)
            } finally {
                state.syncing = false
            }
        }
    }

    private companion object {
        val HOST_WITH_PORT = Regex("""([^:\[\]]+):(\d{1,5})""")
    }
}
