package com.distronode.districtai.core.network

/**
 * Where a 2xx that [DistrictApiClient] could not decode gets reported, beyond the screen.
 *
 * ⛔ WITHOUT THIS, CONTRACT DRIFT REACHED EVERY USER AND NO OPERATOR. A server deploy that renames a
 * field turns a screen into "update the app" ([ApiResult.DecodeFailure]) for everyone on Android,
 * and a crash reporter sees nothing because nothing crashed. The client reports it once, at the one
 * place every decode happens.
 *
 * ⚠️ A SEAM RATHER THAN A SENTRY CALL, so core-network stays free of the crash reporter: the app
 * module is the only one that may name an `io.sentry` type (see app/build.gradle.kts), and it
 * injects the implementation.
 *
 * ⛔ AN IMPLEMENTATION MUST NOT THROW. It is called from inside `decodeBody`, whose whole contract
 * is that nothing escapes it; the client guards the call anyway, and a throw is dropped.
 */
fun interface DecodeFailureReporter {

    fun report(failure: ResponseDecodeFailure)

    companion object {
        /** Reports nothing. The default, so a client built without a reporter behaves as before. */
        val NONE: DecodeFailureReporter = DecodeFailureReporter { }
    }
}

/**
 * One undecodable 2xx, reduced to what may leave the device.
 *
 * ⛔ NO BODY AND NO CAUSE, AND THE CAUSE IS THE SUBTLE ONE. The body preview on
 * [ApiResult.DecodeFailure] can quote a transcript or a contact record, so it is not carried. But
 * kotlinx.serialization's own exception MESSAGE quotes the input too (its "JSON input: ..." tail),
 * so attaching the original exception as a cause would ship the same customer data by another
 * route. What is kept is the serializer's name, the request path (ids only; the query, which can
 * carry an address, is not part of it), the exception's TYPE, and its stack frames, which hold
 * code locations and no data.
 *
 * @property serializer the serial name of the type being decoded, e.g. `OverviewResponse`'s.
 * @property path the request's encoded path, without the query.
 */
class ResponseDecodeFailure(
    val serializer: String,
    val path: String,
    causeType: String,
    frames: Array<StackTraceElement>,
) : RuntimeException("$causeType decoding $serializer from $path") {
    init {
        stackTrace = frames
    }
}

/**
 * Report [cause] without letting the reporter break the decode.
 *
 * ⚠️ A TOP-LEVEL FUNCTION rather than a member, because [DistrictApiClient] sits on detekt's
 * function ceiling; see the note on its other top-level helpers.
 */
@Suppress("TooGenericExceptionCaught")
internal fun DecodeFailureReporter.reportSafely(cause: RuntimeException, serializer: String, path: String) {
    try {
        report(ResponseDecodeFailure(serializer, path, cause.javaClass.name, cause.stackTrace))
    } catch (ignored: RuntimeException) {
        // ⛔ Dropped, not rethrown: a broken reporter must not turn a decode failure into a crash.
        // The user-visible result is the DecodeFailure either way.
    }
}
