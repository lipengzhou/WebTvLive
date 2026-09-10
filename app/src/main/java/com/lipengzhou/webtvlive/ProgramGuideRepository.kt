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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class CachedProgramGuide(
    val date: String,
    val fetchedAtMillis: Long,
    val guide: ProgramGuide,
)

internal fun interface EpgClient {
    fun download(pid: String, date: String): ProgramGuide
}

internal interface ProgramGuideCache {
    fun read(key: String, expectedDate: String): CachedProgramGuide?
    fun write(key: String, entry: CachedProgramGuide)
    fun removeOtherDates(currentDate: String)
}

/**
 * 当天节目单仓库：内存 -> 磁盘 -> 网络。
 *
 * 磁盘、JSON 和网络工作始终在后台执行；结果通过 [callbackDispatcher] 回到调用方线程。
 * 相同频道的并发请求会合并，旧缓存会先返回并在过期时后台刷新。
 */
class ProgramGuideRepository internal constructor(
    private val cache: ProgramGuideCache,
    private val client: EpgClient,
    private val executor: ExecutorService,
    private val callbackDispatcher: (() -> Unit) -> Unit,
    private val todayProvider: () -> String,
    private val nowMillis: () -> Long,
    private val logger: (String, Throwable?) -> Unit,
) {
    sealed interface Result {
        data object Loading : Result
        data class Data(val guide: ProgramGuide, val fromCache: Boolean) : Result
        data class Error(val hasCachedData: Boolean) : Result
    }

    constructor(context: Context) : this(
        cache = SharedPreferencesProgramGuideCache(context.applicationContext),
        client = HttpEpgClient(),
        executor = Executors.newFixedThreadPool(2),
        callbackDispatcher = mainThreadDispatcher(),
        todayProvider = ::today,
        nowMillis = System::currentTimeMillis,
        logger = { message, error ->
            if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
        },
    )

    private val memoryCache = mutableMapOf<String, CachedProgramGuide>()
    private val pendingCallbacks = mutableMapOf<String, MutableList<(Result) -> Unit>>()
    private var activeDate = todayProvider()
    private var closed = false

    init {
        executor.execute { cache.removeOtherDates(activeDate) }
    }

    fun loadToday(pid: String, callback: (Result) -> Unit) {
        if (closed) return
        val date = todayProvider()
        if (date != activeDate) {
            activeDate = date
            memoryCache.clear()
            executor.execute { cache.removeOtherDates(date) }
        }
        val key = cacheKey(pid, date)
        val memoryEntry = memoryCache[key]
        if (memoryEntry != null) {
            callback(Result.Data(memoryEntry.guide, fromCache = true))
            if (isFresh(memoryEntry)) return
            if (enqueueCallback(key, callback)) return
            refreshFromNetwork(key, pid, date, hasCachedData = true)
            return
        }

        callback(Result.Loading)
        if (enqueueCallback(key, callback)) return
        executor.execute {
            val diskEntry = cache.read(key, date)
            callbackDispatcher {
                if (closed || date != activeDate) {
                    pendingCallbacks.remove(key)
                    return@callbackDispatcher
                }
                if (diskEntry != null) {
                    memoryCache[key] = diskEntry
                    pendingCallbacks[key].orEmpty().forEach {
                        it(Result.Data(diskEntry.guide, fromCache = true))
                    }
                    if (isFresh(diskEntry)) {
                        pendingCallbacks.remove(key)
                        return@callbackDispatcher
                    }
                }
                refreshFromNetwork(key, pid, date, hasCachedData = diskEntry != null)
            }
        }
    }

    fun close() {
        closed = true
        pendingCallbacks.clear()
        executor.shutdownNow()
    }

    /** Returns true when another request already owns the load. */
    private fun enqueueCallback(key: String, callback: (Result) -> Unit): Boolean {
        val callbacks = pendingCallbacks[key]
        if (callbacks != null) {
            callbacks += callback
            return true
        }
        pendingCallbacks[key] = mutableListOf(callback)
        return false
    }

    private fun refreshFromNetwork(
        key: String,
        pid: String,
        date: String,
        hasCachedData: Boolean,
    ) {
        logger("Program guide network load: pid=$pid date=$date", null)
        executor.execute {
            val result = runCatching {
                val entry = CachedProgramGuide(date, nowMillis(), client.download(pid, date))
                runCatching { cache.write(key, entry) }
                    .onFailure { error ->
                        runCatching {
                            logger("Unable to cache program guide: pid=$pid date=$date", error)
                        }
                    }
                entry
            }
            callbackDispatcher {
                if (closed) return@callbackDispatcher
                val listeners = pendingCallbacks.remove(key).orEmpty()
                result.onSuccess { entry ->
                    if (date != activeDate) return@onSuccess
                    memoryCache[key] = entry
                    logger(
                        "Program guide loaded: pid=$pid date=$date items=${entry.guide.items.size}",
                        null,
                    )
                    listeners.forEach { it(Result.Data(entry.guide, fromCache = false)) }
                }.onFailure { error ->
                    logger("Unable to load program guide: pid=$pid date=$date", error)
                    listeners.forEach { it(Result.Error(hasCachedData)) }
                }
            }
        }
    }

    private fun isFresh(entry: CachedProgramGuide): Boolean =
        nowMillis() - entry.fetchedAtMillis < CACHE_REFRESH_MS

    private fun cacheKey(pid: String, date: String) = "${CACHE_KEY_PREFIX}${pid}_$date"

    companion object {
        private const val TAG = "WebTvLive"
        internal const val CACHE_KEY_PREFIX = "epg_"
        internal const val CACHE_REFRESH_MS = 30 * 60 * 1000L
        private val BEIJING_ZONE = ZoneId.of("Asia/Shanghai")
        private val DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE

        fun today(): String = LocalDate.now(BEIJING_ZONE).format(DATE_FORMATTER)

        private fun mainThreadDispatcher(): (() -> Unit) -> Unit {
            val handler = Handler(Looper.getMainLooper())
            return { block -> handler.post(block) }
        }
    }
}

private class HttpEpgClient : EpgClient {
    override fun download(pid: String, date: String): ProgramGuide {
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

    private companion object {
        const val EPG_ENDPOINT = "https://capi.yangshipin.cn/api/yspepg/program/"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}

private class SharedPreferencesProgramGuideCache(context: Context) : ProgramGuideCache {
    private val preferences = context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)

    override fun write(key: String, entry: CachedProgramGuide) {
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

    override fun read(key: String, expectedDate: String): CachedProgramGuide? = runCatching {
        val root = JSONObject(preferences.getString(key, null) ?: return null)
        val date = root.getString("date")
        if (date != expectedDate) {
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
        CachedProgramGuide(
            date = date,
            fetchedAtMillis = root.getLong("fetchedAt"),
            guide = ProgramGuide(root.optLong("updateTime"), items),
        )
    }.onFailure {
        preferences.edit().remove(key).apply()
    }.getOrNull()

    override fun removeOtherDates(currentDate: String) {
        val editor = preferences.edit()
        preferences.all.keys
            .filter {
                it.startsWith(ProgramGuideRepository.CACHE_KEY_PREFIX) &&
                    !it.endsWith("_$currentDate")
            }
            .forEach(editor::remove)
        editor.apply()
    }

    private companion object {
        const val CACHE_PREFERENCES = "program_guide_cache"
    }
}
