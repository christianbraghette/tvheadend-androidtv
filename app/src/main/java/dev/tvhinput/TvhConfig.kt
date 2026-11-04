package dev.tvhinput

import android.content.Context
import androidx.core.content.edit

/**
 * How live streams are fetched. Independent of how channels and the guide are synced: over HTSP
 * whenever the server allows it, the HTTP API otherwise (see TvProviderSync).
 */
enum class StreamProtocol { HTTP, HTSP }

data class TvhConfig(
    val host: String,
    val port: Int = 9981,
    val https: Boolean = false,
    val webRoot: String = "",
    val username: String = "",
    val password: String = "",
    val profile: String = "pass",
    val protocol: StreamProtocol = StreamProtocol.HTTP,
    val htspPort: Int = 9982,
)

object Settings {
    private const val PREFS = "tvh_config"

    fun load(context: Context): TvhConfig? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString("host", null)?.takeIf { it.isNotBlank() } ?: return null
        // Older versions stored the password in plain text: move it to the encrypted field.
        p.getString("password", null)?.let { legacy ->
            p.edit {
                putString("password_enc", SecretBox.encrypt(legacy))
                remove("password")
            }
            return load(context)
        }
        return TvhConfig(
            host = host,
            port = p.getInt("port", 9981),
            https = p.getBoolean("https", false),
            webRoot = p.getString("web_root", "").orEmpty(),
            username = p.getString("username", "").orEmpty(),
            password = p.getString("password_enc", null)?.let(SecretBox::decrypt).orEmpty(),
            profile = p.getString("profile", "pass").orEmpty(),
            protocol = runCatching { StreamProtocol.valueOf(p.getString("protocol", null)!!) }
                .getOrDefault(StreamProtocol.HTTP),
            htspPort = p.getInt("htsp_port", 9982),
        )
    }

    fun save(context: Context, config: TvhConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("host", config.host)
            putInt("port", config.port)
            putBoolean("https", config.https)
            putString("web_root", config.webRoot)
            putString("username", config.username)
            putString("password_enc", SecretBox.encrypt(config.password))
            remove("password")
            putString("profile", config.profile)
            putString("protocol", config.protocol.name)
            putInt("htsp_port", config.htspPort)
        }
    }
}
