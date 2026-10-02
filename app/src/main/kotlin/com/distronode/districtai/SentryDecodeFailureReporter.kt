package com.distronode.districtai

import com.distronode.districtai.core.network.DecodeFailureReporter
import com.distronode.districtai.core.network.ResponseDecodeFailure
import io.sentry.ScopeCallback
import io.sentry.Sentry

/**
 * Contract drift and threading faults in the API client, sent to Sentry as handled events.
 *
 * ⛔ THE TWO TAGS ARE THE WHOLE PAYLOAD, AND [ResponseDecodeFailure] IS WHY THAT IS SAFE. It carries
 * no body and no original exception, because both can quote customer data; the serializer and the
 * path are what an operator needs to find the drifted route, and they are tags so Sentry groups
 * and filters on them.
 *
 * ⚠️ A NO-OP WHEN SENTRY WAS NEVER ARMED. A build without a DSN skips `SentryAndroid.init` (see
 * [DistrictSentry]), and `Sentry.captureException` on an un-initialised SDK does nothing.
 *
 * @param capture the Sentry call, injectable so a test can read the tags without arming the
 *   process-wide SDK, which a unit test must not do (see [DistrictSentry.apply]).
 */
internal class SentryDecodeFailureReporter(
    private val capture: (Throwable, ScopeCallback) -> Unit = { failure, scope ->
        Sentry.captureException(failure, scope)
    },
) : DecodeFailureReporter {

    override fun report(failure: ResponseDecodeFailure) {
        capture(failure) { scope ->
            scope.setTag(TAG_SERIALIZER, failure.serializer)
            scope.setTag(TAG_PATH, failure.path)
        }
    }

    internal companion object {
        const val TAG_SERIALIZER = "decode.serializer"
        const val TAG_PATH = "decode.path"
    }
}
