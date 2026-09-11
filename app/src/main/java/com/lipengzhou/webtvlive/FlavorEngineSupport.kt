package com.lipengzhou.webtvlive

/**
 * Platform-independent helpers shared by the `gecko` and `webview` [BrowserEngine] adapters.
 *
 * These pieces are protocol- or policy-coupled and must behave identically across both kernels,
 * so they live in `main` to avoid the "only one flavor was updated" drift that [BrowserProtocol]
 * changes are especially prone to.
 */

/** Desktop UA both kernels send so the live pages serve their full-size player layout. */
const val DESKTOP_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** A channel load requested before the page bridge/port is ready; replayed once it connects. */
internal data class PendingChannelSwitch(
    val channel: Channel,
    val requestId: Long,
)

/** Emits a [BrowserEngine.Event.Failed] with the given kind/detail to this listener. */
internal fun BrowserEngine.Listener.reportFailure(
    kind: BrowserEngine.FailureKind,
    detail: String,
    recoverable: Boolean,
) {
    onEvent(BrowserEngine.Event.Failed(BrowserEngine.Failure(kind, detail, recoverable)))
}

/**
 * Decodes one raw page message and routes it, sharing the logic both flavors need:
 *  - invalid payloads report a non-recoverable `PROTOCOL` failure;
 *  - `Playing` / `ChannelSelected` / `Diagnostic` forward straight to [listener];
 *  - `Ready` is delegated to [onReady] because reconnect handling is kernel-specific
 *    (WebView bridge flag vs. Gecko WebExtension port).
 *
 * Kept `inline` so [onReady] can use a non-local `return` to bail out of the caller, matching the
 * hand-written dispatch it replaces.
 */
internal inline fun dispatchPageEvent(
    message: Any?,
    listener: BrowserEngine.Listener?,
    onReady: () -> Unit,
) {
    when (val decoded = BrowserProtocol.decodeEvent(message)) {
        is BrowserProtocol.DecodeResult.Invalid ->
            listener?.reportFailure(
                BrowserEngine.FailureKind.PROTOCOL,
                decoded.reason,
                recoverable = false,
            )

        is BrowserProtocol.DecodeResult.Success -> when (val event = decoded.event) {
            BrowserProtocol.PageEvent.Ready -> onReady()
            is BrowserProtocol.PageEvent.Playing ->
                listener?.onEvent(BrowserEngine.Event.PlaybackReady(event.requestId))
            is BrowserProtocol.PageEvent.ChannelSelected ->
                listener?.onEvent(BrowserEngine.Event.ChannelSelected(event.channel))
            is BrowserProtocol.PageEvent.Diagnostic ->
                listener?.onEvent(BrowserEngine.Event.Diagnostic(event.message))
        }
    }
}
