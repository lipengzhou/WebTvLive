package com.lipengzhou.webtvlive

import android.app.Application
import android.os.SystemClock
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/** 进程级 GeckoRuntime：全局只创建一次，并提前预热首个内容进程。 */
class WebTvLiveApplication : Application() {

    lateinit var geckoRuntime: GeckoRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        // Gecko 的 GPU、内容、媒体等子进程也会创建 Application；只有主进程持有 Runtime。
        if (getProcessName() != packageName) return

        val startedAt = SystemClock.elapsedRealtime()
        geckoRuntime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .consoleOutput(true)
                // 电视内存有限；关闭站点隔离和独立扩展进程，减少跨域直播页的子进程数量。
                .fissionEnabled(false)
                .extensionsProcessEnabled(false)
                .build(),
        )
        // GeckoView 150+ 不再默认预分配内容进程；官方建议显式 warmUp 首次页面加载。
        geckoRuntime.warmUp()
        Log.i(
            TAG,
            "StartupTiming: runtime_created_and_warmed elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
    }

    companion object {
        private const val TAG = "WebTvLive"
    }
}
