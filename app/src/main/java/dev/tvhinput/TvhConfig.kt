package dev.tvhinput

import android.content.Context

data class TvhConfig(
    val host: String,
    val port: Int = 9981,
    val https: Boolean = false,
    val webRoot: String = "",
    val username: String = "",
    val password: String = "",
    val profile: String = "pass",
)

object Settings {
    private const val PREFS = "tvh_config"

    fun load(context: Context): TvhConfig? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString("host", null)?.takeIf { it.isNotBlank() } ?: return null
        return TvhConfig(
            host = host,
            port = p.getInt("port", 9981),
            https = p.getBoolean("https", false),
            webRoot = p.getString("web_root", "").orEmpty(),
            username = p.getString("username", "").orEmpty(),
            password = p.getString("password", "").orEmpty(),
            profile = p.getString("profile", "pass").orEmpty(),
        )
    }

    fun save(context: Context, config: TvhConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putBoolean("https", config.https)
            .putString("web_root", config.webRoot)
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("profile", config.profile)
            .apply()
    }
}
