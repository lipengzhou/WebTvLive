package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

class EpgProtoDecoderTest {
    @Test
    fun decode_readsOfficialEpgShape() {
        val program = message(
            stringField(1, "22384745"),
            stringField(2, "隐锋第2集"),
            varintField(3, 1_788_933_305),
            varintField(4, 1_788_936_115),
            stringField(5, "13:55"),
            stringField(6, "14:41"),
            varintField(7, 2_810),
            varintField(8, 0),
            stringField(9, "1"),
            stringField(10, "1"),
        )
        val response = message(
            varintField(1, 200),
            bytesField(2, program),
            stringField(3, "成功"),
            varintField(4, 1_788_936_001),
        )

        val guide = EpgProtoDecoder.decode(response)
        val item = guide.items.single()

        assertEquals(1_788_936_001, guide.updateTimeEpochSeconds)
        assertEquals("22384745", item.programId)
        assertEquals("隐锋第2集", item.name)
        assertEquals("13:55", item.startTime)
        assertEquals(2_810, item.durationSeconds)
        assertFalse(item.isVip)
        assertEquals("1", item.copyrightFlag)
        assertEquals("1", item.timeShiftReviewFlag)
    }

    private fun message(vararg fields: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { fields.forEach { write(it) } }.toByteArray()

    private fun stringField(field: Int, value: String) =
        bytesField(field, value.toByteArray(Charsets.UTF_8))

    private fun bytesField(field: Int, value: ByteArray) =
        message(varint((field shl 3 or 2).toLong()), varint(value.size.toLong()), value)

    private fun varintField(field: Int, value: Long) =
        message(varint((field shl 3).toLong()), varint(value))

    private fun varint(value: Long): ByteArray {
        var remaining = value
        return ByteArrayOutputStream().apply {
            while (true) {
                if ((remaining and 0x7f.inv().toLong()) == 0L) {
                    write(remaining.toInt())
                    break
                }
                write((remaining.toInt() and 0x7f) or 0x80)
                remaining = remaining ushr 7
            }
        }.toByteArray()
    }
}
