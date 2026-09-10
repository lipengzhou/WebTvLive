package com.lipengzhou.webtvlive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserProtocolTest {
    private val fixture: JSONObject by lazy {
        val input = requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_PATH))
        JSONObject(input.bufferedReader().use { it.readText() })
    }

    @Test
    fun switchCommandContainsVersionedRequiredFields() {
        val payload = BrowserProtocol.encode(
            BrowserProtocol.Command.SwitchChannel("CCTV13", "600001811", 42),
        )

        assertEquals(1, payload.getInt("protocolVersion"))
        assertEquals("switchChannel", payload.getString("type"))
        assertEquals("CCTV13", payload.getString("channel"))
        assertEquals("600001811", payload.getString("pid"))
        assertEquals(42L, payload.getLong("requestId"))
    }

    @Test
    fun playingEventRequiresCurrentVersionAndPositiveRequestId() {
        val decoded = BrowserProtocol.decodeEvent(fixture.getJSONObject("validEvent"))

        assertEquals(
            BrowserProtocol.DecodeResult.Success(BrowserProtocol.PageEvent.Playing(9)),
            decoded,
        )
        assertTrue(
            BrowserProtocol.decodeEvent(
                "{\"protocolVersion\":1,\"type\":\"playing\",\"requestId\":0}",
            ) is BrowserProtocol.DecodeResult.Invalid,
        )
        assertTrue(
            BrowserProtocol.decodeEvent(
                "{\"protocolVersion\":1,\"type\":\"playing\",\"requestId\":1.5}",
            ) is BrowserProtocol.DecodeResult.Invalid,
        )
    }

    @Test
    fun rejectsUnknownVersionTypeAndOversizedMessages() {
        assertTrue(
            BrowserProtocol.decodeEvent("{\"protocolVersion\":2,\"type\":\"ready\"}")
                is BrowserProtocol.DecodeResult.Invalid,
        )
        assertTrue(
            BrowserProtocol.decodeEvent("{\"protocolVersion\":1,\"type\":\"unknown\"}")
                is BrowserProtocol.DecodeResult.Invalid,
        )
        assertTrue(
            BrowserProtocol.decodeEvent("x".repeat(BrowserProtocol.MAX_EVENT_BYTES + 1))
                is BrowserProtocol.DecodeResult.Invalid,
        )
    }

    @Test
    fun sharedFixtureTracksTheProtocolVersion() {
        assertEquals(BrowserProtocol.VERSION, fixture.getInt("version"))
    }

    private companion object {
        const val FIXTURE_PATH = "browser_protocol_fixture.json"
    }
}
