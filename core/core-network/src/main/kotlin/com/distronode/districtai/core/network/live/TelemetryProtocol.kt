package com.distronode.districtai.core.network.live

import java.net.URI
import java.net.URISyntaxException

/**
 * The live telemetry protocol's constants and timing rules, as pure functions.
 *
 * The protocol, as the server speaks it:
 *
 * 1. `POST /api/district/telemetry/token` with the workspace mints a credential that lives fifteen
 *    minutes and names the socket's address, which depends on the workspace's region.
 * 2. The client opens that address with `?workspaceId=` added and offers two subprotocols:
 *    [SUBPROTOCOL], then the credential as `distronode.token.<token>`. The server must select the
 *    first.
 * 3. The server checks the credential and the membership after the handshake and closes the
 *    socket if either fails: [CLOSE_UNAUTHORIZED] (4401) for the credential, [CLOSE_FORBIDDEN]
 *    (4403) for the membership or a server that does not serve the workspace's region.
 * 4. While open, the server relays the workspace's events as text messages, pings every thirty
 *    seconds, and closes with 4401 once the credential has expired.
 * 5. The client may send three ops ([TranscriptClientOp]). The server keeps them per socket, so
 *    they are sent again on every open (see [TelemetryConnection]).
 *
 * Ported from district-core-swift's `TelemetryProtocol` (7.0.0-beta.1), rule for rule.
 */
object TelemetryProtocol {
    /**
     * ⛔ OFFERED FIRST, AND THE SERVER MUST SELECT IT. A connection on which the server selected
     * anything else, or nothing, is refused: it speaks another version, or it selected the
     * CREDENTIAL, which means it echoed the credential back in a response header.
     */
    const val SUBPROTOCOL: String = "distronode.telemetry.v1"

    /**
     * The prefix of the second subprotocol, which carries the credential.
     *
     * ⛔ THE TOKEN TRAVELS IN `Sec-WebSocket-Protocol`, NEVER IN THE URL, so it stays out of every
     * access log between here and the broadcaster.
     */
    const val TOKEN_SUBPROTOCOL_PREFIX: String = "distronode.token."

    /** The credential was refused. Mint a new one and reconnect. */
    const val CLOSE_UNAUTHORIZED: Int = 4401

    /** The member may not stream this workspace. ⛔ A new credential changes nothing: stop. */
    const val CLOSE_FORBIDDEN: Int = 4403

    /** How long before expiry the socket is replaced. */
    const val RENEWAL_LEAD_MILLIS: Long = 60_000

    /** The shortest a socket runs before it is replaced, so a near-expired credential cannot spin. */
    const val RENEWAL_FLOOR_MILLIS: Long = 5_000

    /** The longest a socket runs before it is replaced, whatever expiry the credential claims. */
    const val RENEWAL_CEILING_MILLIS: Long = 60L * 60 * 1000

    /** The shortest wait before a reconnect. */
    const val BACKOFF_FLOOR_MILLIS: Long = 1_000

    /** The longest wait before a reconnect, jitter included. */
    const val BACKOFF_CAP_MILLIS: Long = 60_000

    /** Three missed server pings: the socket is presumed dead. */
    const val SILENCE_LIMIT_MILLIS: Long = 90_000

    /**
     * How long a socket must have stayed open to count as established. The server checks the
     * credential just after the handshake and closes at once if it fails, so a socket that closes
     * sooner is a failed attempt and the next one backs off.
     */
    const val STABLE_AFTER_MILLIS: Long = 30_000

    /** How long opening a socket (TCP, TLS and the handshake) may take. */
    const val CONNECT_TIMEOUT_MILLIS: Long = 15_000

    /** The most doublings the backoff ceiling takes before the cap governs. */
    private const val MAX_DOUBLINGS = 16

    /** The jitter read for a non-finite one: the middle of the range. */
    private const val MIDDLE_JITTER = 0.5

    private const val WS_SCHEME = "ws"
    private const val WSS_SCHEME = "wss"
    private const val IPV4_OCTETS = 4
    private const val LOOPBACK_FIRST_OCTET = "127"
    private const val MAX_OCTET = 255

    /**
     * The wait before reconnect attempt [failures] (1 for the first retry), for a [jitter] between
     * 0 and 1: "equal jitter", the ceiling doubling from 2 s to [BACKOFF_CAP_MILLIS], the wait
     * between half the ceiling and all of it. No wait is under [BACKOFF_FLOOR_MILLIS].
     */
    fun backoffMillis(failures: Int, jitter: Double): Long {
        val doublings = (failures.coerceAtLeast(1) - 1).coerceAtMost(MAX_DOUBLINGS)
        val ceiling = ((2 * BACKOFF_FLOOR_MILLIS) shl doublings).coerceAtMost(BACKOFF_CAP_MILLIS)
        val spread = if (jitter.isFinite()) jitter.coerceIn(0.0, 1.0) else MIDDLE_JITTER
        return ceiling / 2 + (ceiling * spread / 2).toLong()
    }

    /** How long a socket opened at [now] on a credential expiring at [expiresAt] runs. */
    fun renewalDelayMillis(expiresAt: Long, now: Long): Long =
        (expiresAt - now - RENEWAL_LEAD_MILLIS).coerceIn(RENEWAL_FLOOR_MILLIS, RENEWAL_CEILING_MILLIS)

    /**
     * Whether [token] can be carried in a subprotocol name: not empty, and only the characters of
     * a compact JWT (base64url and dots).
     *
     * ⛔ ANYTHING ELSE WOULD BREAK THE HEADER'S COMMA-SEPARATED LIST.
     */
    fun isPresentable(token: String): Boolean =
        token.isNotEmpty() && token.all { isAsciiAlphanumeric(it) || it in "-_." }

    /** The subprotocols to offer: ⛔ the version FIRST, so a naive server never echoes the token. */
    fun offeredSubprotocols(token: String): List<String> = listOf(SUBPROTOCOL, TOKEN_SUBPROTOCOL_PREFIX + token)

    /**
     * The URL to dial for [workspaceId]: [wsUrl] with the workspace added to the query and any
     * fragment dropped.
     *
     * ⛔ PLAIN `ws` ONLY TO THIS MACHINE, so the credential never crosses a network unencrypted.
     */
    fun socketUrl(wsUrl: String, workspaceId: String): SocketUrl {
        if (wsUrl.isEmpty()) return SocketUrl.Refused(TelemetryEndpointError.MISSING)
        val uri = try {
            URI(wsUrl)
        } catch (_: URISyntaxException) {
            return SocketUrl.Refused(TelemetryEndpointError.INVALID)
        }
        val host = uri.host
        val scheme = uri.scheme?.lowercase()
        val refusal = when {
            host.isNullOrEmpty() -> TelemetryEndpointError.INVALID
            scheme == WSS_SCHEME -> null
            scheme != WS_SCHEME -> TelemetryEndpointError.INVALID
            isLoopback(host) -> null
            else -> TelemetryEndpointError.INSECURE
        }
        if (refusal != null) return SocketUrl.Refused(refusal)
        // ⛔ ENCODED BY HAND: a workspace id carrying `&` or `=` must not add a parameter of its
        // own choosing to the URL the credential is presented at.
        val added = "workspaceId=" + percentEncoded(workspaceId)
        val query = listOfNotNull(uri.rawQuery?.takeIf { it.isNotEmpty() }, added).joinToString("&")
        val withoutFragment = wsUrl.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        return SocketUrl.Accepted("$base?$query")
    }

    /** Every byte outside RFC 3986's unreserved set, as `%XX`. */
    private fun percentEncoded(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val char = (byte.toInt() and MAX_OCTET).toChar()
            if (isAsciiAlphanumeric(char) || char in "-._~") {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and MAX_OCTET))
            }
        }
    }

    /** `A-Z`, `a-z` or `0-9`, and nothing else a locale might call a letter. */
    internal fun isAsciiAlphanumeric(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9'

    /** `localhost`, `::1` (bracketed or bare) or `127.x.y.z`. */
    private fun isLoopback(host: String): Boolean {
        val bare = host.lowercase().trim('[', ']')
        if (bare == "localhost" || bare == "::1") return true
        val octets = bare.split('.')
        // ⚠️ NO RANGE CHECK PER OCTET: `URI` has no host at all for an all-digit host that is not a
        // valid IPv4 address (`127.0.0.300`), so four numeric labels here are always one.
        return octets.size == IPV4_OCTETS &&
            octets[0] == LOOPBACK_FIRST_OCTET &&
            octets.all { it.toIntOrNull() != null }
    }
}

/** What [TelemetryProtocol.socketUrl] made of the server's address. */
sealed interface SocketUrl {
    data class Accepted(val url: String) : SocketUrl

    data class Refused(val error: TelemetryEndpointError) : SocketUrl
}

/** What is wrong with the socket address the server gave. */
enum class TelemetryEndpointError {
    /** The server gave none. Only a development server does that. */
    MISSING,

    /** Not a `wss` or `ws` URL with a host. */
    INVALID,

    /** `ws`, unencrypted, to a host other than this machine. */
    INSECURE,
}
