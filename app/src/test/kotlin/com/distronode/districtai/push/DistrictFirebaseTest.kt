package com.distronode.districtai.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hand-written Firebase configuration, and the one decision in it that can be wrong silently.
 *
 * ⛔ THE APPLICATION-ID MAPPING IS THE WHOLE OF WHAT THE `google-services` PLUGIN WOULD HAVE DONE,
 * and it is the half that fails invisibly. Firebase registers an Android app against its PACKAGE
 * NAME, and this build ships two: `com.distronode.districtai` and, with `applicationIdSuffix`,
 * `com.distronode.districtai.debug`. Handing the release app id to a `.debug` install produces a
 * token FCM later rejects with `SENDER_ID_MISMATCH` — which the server treats as PERMANENT and
 * disables the row for. So the failure surfaces as "push stopped working on that phone", long after
 * the build that caused it, on a device nobody is watching.
 *
 * ⚠️ NOTHING ABOUT DELIVERY IS TESTED HERE, AND NOTHING COULD BE. Waydroid is API 33 with no Play
 * Services, so token acquisition and delivery are real-device work. What is checked is the pure
 * mapping and the fact that an unrecognised package is REFUSED rather than defaulted.
 */
class DistrictFirebaseTest {

    @Test
    fun `each package maps to its own Firebase app id`() {
        assertEquals(
            DistrictFirebase.RELEASE_FIREBASE_APP_ID,
            DistrictFirebase.firebaseAppIdFor(DistrictFirebase.RELEASE_APPLICATION_ID),
        )
        assertEquals(
            DistrictFirebase.DEBUG_FIREBASE_APP_ID,
            DistrictFirebase.firebaseAppIdFor(DistrictFirebase.DEBUG_APPLICATION_ID),
        )
    }

    @Test
    fun `the two app ids are different, which is the point of having two`() {
        // ⚠️ Stated as an assertion because a copy-paste that made them equal would produce a build
        // that works in release and mints permanently-rejected tokens in debug — and every other
        // test here would still pass.
        assertNotEquals(
            DistrictFirebase.RELEASE_FIREBASE_APP_ID,
            DistrictFirebase.DEBUG_FIREBASE_APP_ID,
        )
    }

    @Test
    fun `an unrecognised package is refused rather than defaulted`() {
        // ⛔ NULL RATHER THAN A FALLBACK, AND THIS IS THE WHOLE VALUE OF THE FUNCTION BEING
        // SEPARATE. Falling back to the release id — which is what a `?:` would do — is the one
        // behaviour that produces a token FCM permanently rejects. Refusing leaves push simply
        // absent, which every path in this app already tolerates: the inbox has a poll and an
        // unanswered ring falls through to PSTN.
        assertNull(DistrictFirebase.firebaseAppIdFor("com.distronode.districtai.staging"))
        assertNull(DistrictFirebase.firebaseAppIdFor("com.example.other"))
        assertNull(DistrictFirebase.firebaseAppIdFor(""))
    }

    @Test
    fun `the debug package is the release package plus the manifest suffix`() {
        // ⚠️ PINS THE ONE COUPLING BETWEEN THIS FILE AND `app/build.gradle.kts`. The suffix is
        // `applicationIdSuffix = ".debug"` there; if it ever changes, the debug build starts
        // reporting a package Firebase has never seen and push goes quiet in debug only.
        assertEquals(
            "${DistrictFirebase.RELEASE_APPLICATION_ID}.debug",
            DistrictFirebase.DEBUG_APPLICATION_ID,
        )
    }

    @Test
    fun `options carry all four fields FCM requires`() {
        // ⚠️ THE BUILDER ENFORCES ONLY TWO OF THEM. `setApplicationId` and `setApiKey` are checked
        // at build time; the project id and the sender id are not, and omitting either produces a
        // `FirebaseApp` that initialises cleanly and then fails at token acquisition with a message
        // about the project rather than about the configuration.
        val options = DistrictFirebase.optionsFor(DistrictFirebase.RELEASE_APPLICATION_ID)

        assertNotNull(options)
        assertEquals(DistrictFirebase.RELEASE_FIREBASE_APP_ID, options!!.applicationId)
        assertEquals(DistrictFirebase.API_KEY, options.apiKey)
        assertEquals(DistrictFirebase.PROJECT_ID, options.projectId)
        assertEquals(DistrictFirebase.GCM_SENDER_ID, options.gcmSenderId)
    }

    @Test
    fun `the debug build gets its own options, not the release ones`() {
        val debug = DistrictFirebase.optionsFor(DistrictFirebase.DEBUG_APPLICATION_ID)

        assertNotNull(debug)
        assertEquals(DistrictFirebase.DEBUG_FIREBASE_APP_ID, debug!!.applicationId)
        // ⚠️ THE PROJECT AND SENDER ARE SHARED, DELIBERATELY: one Firebase project holds both
        // Android apps, which is why the sender id is the same and only the app id differs.
        assertEquals(DistrictFirebase.PROJECT_ID, debug.projectId)
        assertEquals(DistrictFirebase.GCM_SENDER_ID, debug.gcmSenderId)
    }

    @Test
    fun `an unrecognised package produces no options at all`() {
        assertNull(DistrictFirebase.optionsFor("com.example.other"))
    }

    @Test
    fun `the sender id is the project NUMBER and the project id is the name`() {
        // ⛔ THEY ARE DIFFERENT VALUES AND SWAPPING THEM IS A PLAUSIBLE MISTAKE. FCM's registration
        // handshake keys on the NUMBER, and a token minted against the wrong one is later rejected
        // with `SENDER_ID_MISMATCH`, which the server treats as permanent. ⚠️ The project must also
        // be the one the server builds its FCM endpoint from: the two halves must name the same
        // Firebase project or nothing is delivered.
        assertEquals("distronode", DistrictFirebase.PROJECT_ID)
        assertEquals("409911651910", DistrictFirebase.GCM_SENDER_ID)
        assertEquals(
            "the app ids embed the sender id, so a mismatch here is self-evident",
            true,
            DistrictFirebase.RELEASE_FIREBASE_APP_ID.startsWith("1:${DistrictFirebase.GCM_SENDER_ID}:android:"),
        )
    }
}
