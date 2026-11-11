package tv.mochimochi.tvhinput

import android.content.ComponentName
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputService

/** The system-facing TV input: Live Channels and other TIF apps bind to this to tune tvheadend channels. */
class TvhInputService : TvInputService() {

    // TV apps bind this service while they use it: one HTSP connection meanwhile, for the guide
    // and for every session's streams.
    private var hub: HtspHub? = null

    override fun onCreate() {
        super.onCreate()
        hub = HtspHub(this).also { it.start() }
    }

    override fun onDestroy() {
        hub?.close()
        hub = null
        super.onDestroy()
    }

    override fun onCreateSession(inputId: String): Session? {
        val config = Settings.load(this) ?: return null
        val hub = hub ?: return null
        return TvhSession(this, TvhClient(config), hub::connection)
    }

    companion object {
        fun inputId(context: Context): String =
            TvContract.buildInputId(ComponentName(context, TvhInputService::class.java))
    }
}
