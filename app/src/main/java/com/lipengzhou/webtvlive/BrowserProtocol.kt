package com.lipengzhou.webtvlive

import org.json.JSONObject

/** Versioned JSON protocol shared by Android browser adapters and the page adapter. */
object BrowserProtocol {
    const val VERSION = 1
    const val MAX_EVENT_BYTES = 16 * 1024

    sealed interface Command {
        data class SwitchChannel(
            val channel: String,
            val pid: String,
            val requestId: Long,
        ) : Command

        data class SetVideoEnhancement(val level: VideoEnhancement) : Command
    }

    sealed interface PageEvent {
        data object Ready : PageEvent
        data class Playing(val requestId: Long) : PageEvent
        data class ChannelSelected(val channel: String) : PageEvent
        data class Diagnostic(val message: String) : PageEvent
    }

    sealed interface DecodeResult {
        data class Success(val event: PageEvent) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }

    fun encode(command: Command): JSONObject = JSONObject().apply {
        put(KEY_VERSION, VERSION)
        when (command) {
            is Command.SwitchChannel -> {
                put(KEY_TYPE, TYPE_SWITCH_CHANNEL)
                put(KEY_CHANNEL, command.channel)
                put(KEY_PID, command.pid)
                put(KEY_REQUEST_ID, command.requestId)
            }

            is Command.SetVideoEnhancement -> {
                put(KEY_TYPE, TYPE_SET_VIDEO_ENHANCEMENT)
                put(KEY_LEVEL, command.level.wireValue)
            }
        }
    }

    fun decodeEvent(raw: Any?): DecodeResult {
        val payload = runCatching {
            when (raw) {
                is JSONObject -> raw
                is String -> {
                    require(raw.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES) {
                        "页面消息超过大小限制"
                    }
                    JSONObject(raw)
                }
                else -> error("页面消息不是 JSON 对象")
            }
        }.getOrElse { return DecodeResult.Invalid(it.message ?: "页面消息无法解析") }
        if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) {
            return DecodeResult.Invalid("页面消息超过大小限制")
        }

        val version = payload.optInt(KEY_VERSION, -1)
        if (version != VERSION) {
            return DecodeResult.Invalid("不支持的页面协议版本：$version")
        }

        return runCatching {
            val event = when (val type = payload.getString(KEY_TYPE)) {
                TYPE_READY -> PageEvent.Ready
                TYPE_PLAYING -> PageEvent.Playing(payload.requirePositiveLong(KEY_REQUEST_ID))
                TYPE_CHANNEL_SELECTED -> PageEvent.ChannelSelected(
                    payload.getString(KEY_CHANNEL).also {
                        require(it.isNotBlank()) { "频道名不能为空" }
                    },
                )
                TYPE_DIAGNOSTIC -> PageEvent.Diagnostic(
                    payload.optString(KEY_MESSAGE).ifBlank { payload.optString(KEY_DETAIL) },
                )
                else -> error("未知页面消息：$type")
            }
            DecodeResult.Success(event)
        }.getOrElse { DecodeResult.Invalid(it.message ?: "页面消息字段无效") }
    }

    private fun JSONObject.requirePositiveLong(name: String): Long {
        val value = get(name)
        require(value is Number) { "$name 必须是数字" }
        val doubleValue = value.toDouble()
        val longValue = value.toLong()
        require(doubleValue.isFinite() && doubleValue == longValue.toDouble() && longValue > 0) {
            "$name 必须是正整数"
        }
        return longValue
    }

    private const val KEY_VERSION = "protocolVersion"
    private const val KEY_TYPE = "type"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_PID = "pid"
    private const val KEY_REQUEST_ID = "requestId"
    private const val KEY_LEVEL = "level"
    private const val KEY_MESSAGE = "message"
    private const val KEY_DETAIL = "detail"

    private const val TYPE_READY = "ready"
    private const val TYPE_SWITCH_CHANNEL = "switchChannel"
    private const val TYPE_SET_VIDEO_ENHANCEMENT = "setVideoEnhancement"
    private const val TYPE_PLAYING = "playing"
    private const val TYPE_CHANNEL_SELECTED = "channelSelected"
    private const val TYPE_DIAGNOSTIC = "diagnostic"
}
