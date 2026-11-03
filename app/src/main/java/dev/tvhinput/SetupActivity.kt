package dev.tvhinput

import android.app.Activity
import android.media.tv.TvInputInfo
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import dev.tvhinput.htsp.HtspConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup screen. Launched by the system TV app when the user adds this input,
 * and also from the launcher to change the server or force a resync.
 */
class SetupActivity : Activity() {
    private val scope = MainScope()

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var https: CheckBox
    private lateinit var webRoot: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var profile: EditText
    private lateinit var protocol: RadioGroup
    private lateinit var htspPort: EditText
    private lateinit var connect: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        https = findViewById(R.id.https)
        webRoot = findViewById(R.id.web_root)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        profile = findViewById(R.id.profile)
        protocol = findViewById(R.id.protocol)
        htspPort = findViewById(R.id.htsp_port)
        connect = findViewById(R.id.connect)
        status = findViewById(R.id.status)

        Settings.load(this)?.let { c ->
            host.setText(c.host)
            port.setText(c.port.toString())
            https.isChecked = c.https
            webRoot.setText(c.webRoot)
            username.setText(c.username)
            password.setText(c.password)
            profile.setText(c.profile)
            protocol.check(if (c.protocol == StreamProtocol.HTSP) R.id.protocol_htsp else R.id.protocol_http)
            htspPort.setText(c.htspPort.toString())
        }
        connect.setOnClickListener { connectAndSync() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun readForm(): TvhConfig? {
        val h = host.text.toString().trim().substringAfter("://").trimEnd('/')
        if (h.isEmpty()) {
            host.error = getString(R.string.host)
            return null
        }
        return TvhConfig(
            host = h,
            port = port.text.toString().toIntOrNull() ?: 9981,
            https = https.isChecked,
            webRoot = webRoot.text.toString().trim(),
            username = username.text.toString().trim(),
            password = password.text.toString(),
            profile = profile.text.toString().trim(),
            protocol = if (protocol.checkedRadioButtonId == R.id.protocol_htsp) StreamProtocol.HTSP else StreamProtocol.HTTP,
            htspPort = htspPort.text.toString().toIntOrNull() ?: 9982,
        )
    }

    private fun connectAndSync() {
        val config = readForm() ?: return
        connect.isEnabled = false
        status.text = "Connecting…"
        scope.launch {
            try {
                val client = TvhClient(config)
                val info = withContext(Dispatchers.IO) { client.serverInfo() }
                if (config.protocol == StreamProtocol.HTSP) {
                    status.text = "Checking HTSP…"
                    // Fail here, not at the first tune, if HTSP is unreachable or not allowed.
                    withContext(Dispatchers.IO) { HtspConnection.connect(config).close() }
                }
                Settings.save(this@SetupActivity, config)

                status.text = "Connected to ${info.name} ${info.version} (${config.protocol}). Importing channels…"
                val sync = TvProviderSync(applicationContext, client)
                val channels = withContext(Dispatchers.IO) { sync.syncChannels() }

                status.text = "Imported ${channels.size} channels. Loading program guide…"
                val programs = withContext(Dispatchers.IO) { sync.syncPrograms(channels) }
                SyncWorker.schedule(applicationContext)

                status.text = "Done: ${channels.size} channels, $programs programs."
                setResult(RESULT_OK)
                // When opened from the system TV app's "add source" flow, return to it.
                if (intent.hasExtra(TvInputInfo.EXTRA_INPUT_ID)) finish()
            } catch (e: Exception) {
                status.text = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                connect.isEnabled = true
            }
        }
    }
}
