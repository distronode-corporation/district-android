package com.distronode.districtai.call

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The type mask [CallForegroundService] claims.
 *
 * ⛔ THE MICROPHONE TYPE IS CONDITIONAL AND THE PHONE-CALL TYPE IS NOT. On API 34+ a
 * microphone-typed foreground service whose app does not hold `RECORD_AUDIO` throws at
 * `startForeground`, inside the service, where nothing catches it; a fresh install answering its
 * first call is exactly that case. The mask is the one decision in the service that a
 * test host can hold, so it is held here.
 */
class ForegroundServiceTypesTest {

    @Test
    fun `with the microphone granted the service is a phone call that uses the microphone`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypes(microphoneGranted = true),
        )
    }

    @Test
    fun `without the microphone the service is a phone call and claims nothing it cannot back`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            foregroundServiceTypes(microphoneGranted = false),
        )
    }
}
