package com.distronode.districtai

/**
 * Whatever holds the process's one [AppContainer].
 *
 * ⚠️ AN INTERFACE SO [MainActivity] CAN BE DRIVEN WITH A CONTAINER WHOSE NETWORK IS FAKED.
 * [DistrictApplication] builds its container from `ApiEnvironment.baseUrl`, a `BuildConfig`
 * constant, so an activity test against it could only ever reach PRODUCTION. A test application
 * implementing this hands the activity a container built with [AppContainerSeams] instead.
 * Production has exactly one implementation, so the single-container guarantee is unchanged.
 */
interface AppContainerOwner {
    val container: AppContainer
}
