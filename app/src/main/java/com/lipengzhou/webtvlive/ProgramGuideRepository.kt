package com.lipengzhou.webtvlive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * 当天节目单仓库：内存 -> 磁盘 -> 网络。
 *
 * 缓存命中后立即回调；超过刷新间隔时仍先展示缓存，再在后台更新。相同频道的并发请求会合并。
 */
class ProgramGuideRepository(context: Context) {
    sealed interface Result {
        data object Loading : Result
        data class Data(val guide: ProgramGuide, val fromCache: Boolean) : Result
        data class Error(val hasCachedData: Boolean) : Result
    }

    private data class CacheEntry(
        val date: String,
        val fetchedAtMillis: Long,
        val guide: ProgramGuide,
    )

    private val preferences = context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryCache = mutableMapOf<String, CacheEntry>()
    private val pendingCallbacks = mutableMapOf<String, MutableList<(Result) -> Unit>>()
    private var activeDate = today()
    private var closed = false

    init {
        removeOldDiskEntries(activeDate)
    }

    fun loadToday(pid: String, callback: (Result) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        val date = today()
        if (date != activeDate) {
            activeDate = date
            memoryCache.clear()
            removeOldDiskEntries(date)
        }
        val key = cacheKey(pid, date)
        val memoryEntry = memoryCache[key]
        val cached = memoryEntry ?: readDiskEntry(key)?.also { memoryCache[key] = it }
        if (cached != null) {
            Log.i(
                TAG,
                "Program guide cache hit: pid=$pid date=$date " +
                    "source=${if (memoryEntry != null) "memory" else "disk"}",
            )
            callback(Result.Data(cached.guide, fromCache = true))
            if (System.currentTimeMillis() - cached.fetchedAtMillis < CACHE_REFRESH_MS) return
        } else {
            callback(Result.Loading)
        }

        val callbacks = pendingCallbacks[key]
        if (callbacks != null) {
            callbacks += callback
            return
        }
        pendingCallbacks[key] = mutableListOf(callback)
        Log.i(TAG, "Program guide network load: pid=$pid date=$date")
        executor.execute {
            val result = runCatching { download(pid, date) }
            mainHandler.post {
                if (closed) return@post
                val listeners = pendingCallbacks.remove(key).orEmpty()
                result.onSuccess { guide ->
                    if (date != activeDate) return@onSuccess
                    val entry = CacheEntry(date, System.currentTimeMillis(), guide)
                    memoryCache[key] = entry
                    writeDiskEntry(key, entry)
                    Log.i(TAG, "Program guide loaded: pid=$pid date=$date items=${guide.items.size}")
                    listeners.forEach { it(Result.Data(guide, fromCache = false)) }
                }.onFailure { error ->
                    Log.w(TAG, "Unable to load program guide: pid=$pid date=$date", error)
                    listeners.forEach { it(Result.Error(hasCachedData = cached != null)) }
                }
            }
        }
    }

    fun close() {
        closed = true
        pendingCallbacks.clear()
        executor.shutdownNow()
    }

    private fun download(pid: String, date: String): ProgramGuide {
        val connection = URL("$EPG_ENDPOINT$pid/$date").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/octet-stream")
            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) { "HTTP $status" }
            EpgProtoDecoder.decode(connection.inputStream.use(::readLimitedBytes))
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedBytes(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESPONSE_BYTES) { "节目单响应过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeDiskEntry(key: String, entry: CacheEntry) {
        val programs = JSONArray()
        entry.guide.items.forEach { item ->
            programs.put(
                JSONObject()
                    .put("programId", item.programId)
                    .put("name", item.name)
                    .put("st", item.startEpochSeconds)
                    .put("et", item.endEpochSeconds)
                    .put("startTime", item.startTime)
                    .put("endTime", item.endTime)
                    .put("duration", item.durationSeconds)
                    .put("isVip", item.isVip)
                    .put("copyrightFlag", item.copyrightFlag)
                    .put("timeShiftReviewFlag", item.timeShiftReviewFlag),
            )
        }
        val value = JSONObject()
            .put("date", entry.date)
            .put("fetchedAt", entry.fetchedAtMillis)
            .put("updateTime", entry.guide.updateTimeEpochSeconds)
            .put("items", programs)
            .toString()
        preferences.edit().putString(key, value).apply()
    }

    private fun readDiskEntry(key: String): CacheEntry? = runCatching {
        val root = JSONObject(preferences.getString(key, null) ?: return null)
        val date = root.getString("date")
        if (date != today()) {
            preferences.edit().remove(key).apply()
            return null
        }
        val array = root.getJSONArray("items")
        val items = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    ProgramGuideItem(
                        programId = item.getString("programId"),
                        name = item.getString("name"),
                        startEpochSeconds = item.getLong("st"),
                        endEpochSeconds = item.getLong("et"),
                        startTime = item.getString("startTime"),
                        endTime = item.getString("endTime"),
                        durationSeconds = item.getInt("duration"),
                        isVip = item.optBoolean("isVip"),
                        copyrightFlag = item.optString("copyrightFlag"),
                        timeShiftReviewFlag = item.optString("timeShiftReviewFlag"),
                    ),
                )
            }
        }
        CacheEntry(
            date = date,
            fetchedAtMillis = root.getLong("fetchedAt"),
            guide = ProgramGuide(root.optLong("updateTime"), items),
        )
    }.onFailure {
        preferences.edit().remove(key).apply()
    }.getOrNull()

    private fun removeOldDiskEntries(currentDate: String) {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(CACHE_KEY_PREFIX) && !it.endsWith("_$currentDate") }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun cacheKey(pid: String, date: String) = "${CACHE_KEY_PREFIX}${pid}_$date"

    companion object {
        private const val TAG = "WebTvLive"
        private const val CACHE_PREFERENCES = "program_guide_cache"
        private const val CACHE_KEY_PREFIX = "epg_"
        private const val EPG_ENDPOINT = "https://capi.yangshipin.cn/api/yspepg/program/"
        private const val CACHE_REFRESH_MS = 30 * 60 * 1000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private val BEIJING_ZONE = ZoneId.of("Asia/Shanghai")
        private val DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE

        fun today(): String = LocalDate.now(BEIJING_ZONE).format(DATE_FORMATTER)
    }
}
