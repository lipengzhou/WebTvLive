package com.lipengzhou.webtvlive

import java.nio.charset.StandardCharsets

/** 解码央视频 `epgProgramModel.Response` protobuf，不引入完整 protobuf 运行时。 */
object EpgProtoDecoder {
    fun decode(bytes: ByteArray): ProgramGuide {
        val reader = ProtoReader(bytes)
        var code = 0
        var message = ""
        var updateTime = 0L
        val items = mutableListOf<ProgramGuideItem>()

        while (reader.hasRemaining()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            when (field) {
                1 -> code = reader.readUInt32(tag)
                2 -> items += decodeProgram(reader.readBytes(tag))
                3 -> message = reader.readString(tag)
                4 -> updateTime = reader.readUInt64(tag)
                else -> reader.skipField(tag)
            }
        }
        require(code == 200) { message.ifBlank { "节目单接口返回 code=$code" } }
        return ProgramGuide(updateTimeEpochSeconds = updateTime, items = items)
    }

    private fun decodeProgram(bytes: ByteArray): ProgramGuideItem {
        val reader = ProtoReader(bytes)
        var programId = ""
        var name = ""
        var startEpochSeconds = 0L
        var endEpochSeconds = 0L
        var startTime = ""
        var endTime = ""
        var durationSeconds = 0
        var isVip = false
        var copyrightFlag = ""
        var timeShiftReviewFlag = ""

        while (reader.hasRemaining()) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                1 -> programId = reader.readString(tag)
                2 -> name = reader.readString(tag)
                3 -> startEpochSeconds = reader.readUInt64(tag)
                4 -> endEpochSeconds = reader.readUInt64(tag)
                5 -> startTime = reader.readString(tag)
                6 -> endTime = reader.readString(tag)
                7 -> durationSeconds = reader.readUInt32(tag)
                8 -> isVip = reader.readUInt32(tag) != 0
                9 -> copyrightFlag = reader.readString(tag)
                10 -> timeShiftReviewFlag = reader.readString(tag)
                else -> reader.skipField(tag)
            }
        }
        return ProgramGuideItem(
            programId = programId,
            name = name,
            startEpochSeconds = startEpochSeconds,
            endEpochSeconds = endEpochSeconds,
            startTime = startTime,
            endTime = endTime,
            durationSeconds = durationSeconds,
            isVip = isVip,
            copyrightFlag = copyrightFlag,
            timeShiftReviewFlag = timeShiftReviewFlag,
        )
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < bytes.size

        fun readUInt32(tag: Int): Int {
            requireWireType(tag, WIRE_VARINT)
            return readVarint().toInt()
        }

        fun readUInt64(tag: Int): Long {
            requireWireType(tag, WIRE_VARINT)
            return readVarint()
        }

        fun readString(tag: Int): String =
            String(readBytes(tag), StandardCharsets.UTF_8)

        fun readBytes(tag: Int): ByteArray {
            requireWireType(tag, WIRE_LENGTH_DELIMITED)
            val length = readVarint().toInt()
            require(length >= 0 && position + length <= bytes.size) { "无效的 protobuf 长度" }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (shift < 64 && position < bytes.size) {
                val current = bytes[position++].toInt() and 0xff
                value = value or ((current and 0x7f).toLong() shl shift)
                if (current and 0x80 == 0) return value
                shift += 7
            }
            error("无效的 protobuf varint")
        }

        fun skipField(tag: Int) {
            when (tag and 0x7) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED_64 -> skip(8)
                WIRE_LENGTH_DELIMITED -> skip(readVarint().toInt())
                WIRE_FIXED_32 -> skip(4)
                else -> error("不支持的 protobuf wire type: ${tag and 0x7}")
            }
        }

        private fun skip(length: Int) {
            require(length >= 0 && position + length <= bytes.size) { "protobuf 数据越界" }
            position += length
        }

        private fun requireWireType(tag: Int, expected: Int) {
            require(tag and 0x7 == expected) { "protobuf 字段类型不匹配" }
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED_64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED_32 = 5
}
