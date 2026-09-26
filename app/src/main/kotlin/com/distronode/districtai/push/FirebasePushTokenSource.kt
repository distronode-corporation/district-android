package com.distronode.districtai.push

import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The real [PushTokenSource]: whatever token the FCM SDK currently holds for this installation.
 *
 * ⛔ EVERY FAILURE ANSWERS `null`, AND ON THIS PATH THAT IS THE COMMON CASE RATHER THAN THE EDGE.
 * `FirebaseMessaging.getInstance()` throws `IllegalStateException` when no default `FirebaseApp`
 * exists — which is exactly what [DistrictFirebase.initialize] leaves behind for a package Firebase
 * has never been told about, on purpose — and the token task itself fails outright on any device
 * without Google Play Services, including every Waydroid image this project can test on. Neither is
 * a fault to report: push is a courtesy channel, and the caller's contract (see [PushRegistrar]) is
 * that a null token means no request is made.
 *
 * ⛔ THE TASK IS BRIDGED BY HAND RATHER THAN WITH `kotlinx-coroutines-play-services`, AND THAT IS A
 * DEPENDENCY DECISION RATHER THAN AN OVERSIGHT. `Task.await()` lives in a separate artifact, and
 * adding one to this project costs a version-catalog entry, a lockfile regeneration across five
 * configurations and a new coordinate in the SBOM the CI scanner reads — all to replace the twelve
 * lines below, on the one call site that will ever need it.
 *
 * ⚠️ `invokeOnCancellation` IS DELIBERATELY ABSENT, BECAUSE THERE IS NOTHING TO CANCEL. A Play
 * Services `Task` exposes no cancellation for this call; the listener is simply never resumed twice
 * (`suspendCancellableCoroutine` drops a resume after cancellation), so a caller that timed out
 * leaves the task to complete into nothing.
 *
 * ⚠️ NONE OF THIS IS VERIFIABLE ON THIS MACHINE. That is why the interface exists and why
 * [PushRegistrar]'s tests drive a fake rather than this class.
 */
class FirebasePushTokenSource internal constructor(
    /**
     * The SDK's token call. ⚠️ A seam so the failure paths (no `FirebaseApp`, a task that fails, is
     * cancelled or answers blank) are asserted against real `Task` objects; production passes
     * nothing. Invoked inside the `runCatching`, because `getInstance()` is what throws.
     */
    private val tokenTask: () -> Task<String>,
) : PushTokenSource {

    constructor() : this({ FirebaseMessaging.getInstance().token })

    override suspend fun currentToken(): String? {
        val task = runCatching(tokenTask).getOrNull() ?: return null
        return suspendCancellableCoroutine { continuation ->
            task
                // ⚠️ ONE LISTENER FOR BOTH OUTCOMES. `addOnSuccessListener` +
                // `addOnFailureListener` would leave a cancelled task resuming neither, and a
                // coroutine that is never resumed is a leak rather than a failure.
                .addOnCompleteListener { task ->
                    continuation.resume(
                        // ⚠️ Blank is normalised to null here rather than at the call site: the SDK
                        // can answer an empty string while it is still provisioning, and the two
                        // mean the same thing to everything downstream.
                        task.takeIf { it.isSuccessful }?.result?.takeIf { it.isNotBlank() },
                    )
                }
        }
    }
}
