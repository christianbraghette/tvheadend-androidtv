package dev.tvhinput

import android.content.ComponentName
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputService

/** The system-facing TV input: Live Channels and other TIF apps bind to this to tune tvheadend channels. */
class TvhInputService : TvInputService() {

    // TV apps bind this service while they use it: keep their guide current meanwhile.
    private var guideMonitor: GuideMonitor? = null

    override fun onCreate() {
        super.onCreate()
        guideMonitor = GuideMonitor(this).also { it.start() }
    }

    override fun onDestroy() {
        guideMonitor?.stop()
        guideMonitor = null
        super.onDestroy()
    }

    override fun onCreateSession(inputId: String): Session? {
        val config = Settings.load(this) ?: return null
        return TvhSession(this, TvhClient(config))
    }

    companion object {
        fun inputId(context: Context): String =
            TvContract.buildInputId(ComponentName(context, TvhInputService::class.java))
    }
}
