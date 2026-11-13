package io.github.thewisekd.phopencode.feature.preview.ui.page

/**
 * Takes WebRTC out of the preview's pages (P13.22). WebRTC reaches the network outside both of the
 * preview's fences: [RequestGate] sees only requests the WebView asks it about, and the bridge's
 * `Content-Security-Policy` has no directive for ICE. A page could otherwise make the WebView ask a
 * STUN or TURN server of its choice, and learn the phone's addresses from the answer.
 *
 * [PreviewWebViews.create] registers [SCRIPT] through [DocumentStartScripts] (androidx.webkit's
 * `addDocumentStartJavaScript`), which runs it in each frame whose origin matches [ORIGIN_RULES]
 * when a document starts to load, before any of the page's scripts. It defines each of [GLOBALS] on `window` as `undefined`,
 * not writable and not configurable: a page can neither assign nor `delete` it back, nor define it
 * again. Nothing of the app is given to the page by it.
 *
 * What it cannot reach is listed under "Known limits" in docs/SECURITY-MODEL.md.
 */
internal object WebRtcBlock {
    /**
     * Every WebRTC interface a page could use, prefixed ones included. The ones that gather ICE
     * candidates are `RTCPeerConnection` and its prefixed forms, and the stand-alone transports
     * `RTCIceTransport` and `RTCIceGatherer` where a WebView has them; the rest go too, so that
     * nothing of the API is left to probe or to rebuild from.
     */
    val GLOBALS: List<String> = listOf(
        "RTCPeerConnection",
        "webkitRTCPeerConnection",
        "mozRTCPeerConnection",
        "RTCIceTransport",
        "RTCIceGatherer",
        "RTCDataChannel",
        "RTCSessionDescription",
        "RTCIceCandidate",
        "RTCRtpSender",
        "RTCRtpReceiver",
        "RTCRtpTransceiver",
        "RTCCertificate",
        "RTCDtlsTransport",
        "RTCSctpTransport",
        "RTCDTMFSender",
        "RTCDTMFToneChangeEvent",
        "RTCDataChannelEvent",
        "RTCPeerConnectionIceEvent",
        "RTCPeerConnectionIceErrorEvent",
        "RTCTrackEvent",
        "RTCError",
        "RTCErrorEvent",
        "RTCStatsReport",
        "RTCEncodedAudioFrame",
        "RTCEncodedVideoFrame",
        "RTCRtpScriptTransform",
    )

    /** `*` matches every origin, opaque ones included: each frame the preview can show. */
    val ORIGIN_RULES: Set<String> = setOf("*")

    /** The script, with [GLOBALS] in it; it keeps going past a name it cannot redefine. */
    val SCRIPT: String = buildString {
        append("(function () {\n")
        append("  'use strict';\n")
        append("  var names = [")
        append(GLOBALS.joinToString(", ") { "\"$it\"" })
        append("];\n")
        append("  for (var i = 0; i < names.length; i++) {\n")
        append("    try {\n")
        append("      Object.defineProperty(window, names[i], ")
        append("{ value: undefined, writable: false, enumerable: false, configurable: false });\n")
        append("    } catch (e) {}\n")
        append("  }\n")
        append("})();\n")
    }
}
