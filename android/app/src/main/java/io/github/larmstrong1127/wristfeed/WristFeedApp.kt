package io.github.larmstrong1127.wristfeed

import android.app.Application
import io.github.larmstrong1127.wristfeed.data.RefreshWorker

class WristFeedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // KEEP policy: re-enqueuing on every process start is harmless.
        RefreshWorker.schedulePeriodic(this)
    }
}
