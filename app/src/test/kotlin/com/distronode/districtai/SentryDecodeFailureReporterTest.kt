package com.distronode.districtai

import com.distronode.districtai.core.network.ResponseDecodeFailure
import io.sentry.Scope
import io.sentry.SentryOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Sentry side of the decode-failure seam, asserted without arming Sentry.
 *
 * ⚠️ NO `SentryAndroid.init`, for the reason [DistrictSentryTest] gives: it installs process-wide
 * handlers a test cannot remove. The capture call is injected and handed a plain [Scope].
 */
class SentryDecodeFailureReporterTest {

    private val failure = ResponseDecodeFailure(
        serializer = "com.distronode.districtai.core.model.OverviewResponse",
        path = "/api/district/overview",
        causeType = "kotlinx.serialization.SerializationException",
        frames = emptyArray(),
    )

    @Test
    fun `the failure is captured tagged with its serializer and path`() {
        val captured = mutableListOf<Throwable>()
        val scope = Scope(SentryOptions())
        val reporter = SentryDecodeFailureReporter { thrown, callback ->
            captured += thrown
            callback.run(scope)
        }

        reporter.report(failure)

        assertSame(failure, captured.single())
        assertEquals(
            mapOf(
                SentryDecodeFailureReporter.TAG_SERIALIZER to "com.distronode.districtai.core.model.OverviewResponse",
                SentryDecodeFailureReporter.TAG_PATH to "/api/district/overview",
            ),
            scope.tags,
        )
    }

    @Test
    fun `the default capture is safe to call with Sentry never armed`() {
        // ⚠️ What a build without a DSN does on every drifted response: nothing, and no throw.
        SentryDecodeFailureReporter().report(failure)
    }
}
