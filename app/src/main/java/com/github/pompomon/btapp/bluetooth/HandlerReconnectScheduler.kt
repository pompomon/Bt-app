package com.github.pompomon.btapp.bluetooth

import android.os.Handler
import android.os.Looper

internal class HandlerReconnectScheduler : ReconnectScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        cancel()
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (pending === runnable) pending = null
            task()
        }
        pending = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    override fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}
