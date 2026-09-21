package com.distronode.districtai

/**
 * The single origin this app talks to.
 *
 * ⛔ ONE BASE URL, ON PURPOSE, AND IT IS NOT AN OVERSIGHT. District AI serves four
 * regional origins (us on GCE, ca/eu/apac on AWS EC2), each with its own
 * PostgreSQL and its own hostname. A browser never picks between them: the
 * Cloudflare geo-router does, and the website itself fans out across region shards
 * when a row lives elsewhere. A native client that tried to choose an origin would
 * be reimplementing edge routing with less information than the edge has.
 *
 * ⚠️ This is only viable BECAUSE authentication is a bearer token. The browser
 * session is a host-only cookie, so it cannot cross regional hostnames; the native
 * access token carries no host binding. If anything here ever moves back to
 * cookies, this decision has to be revisited with it.
 */
object ApiEnvironment {

    /** Origin, scheme included, with no trailing slash. */
    val baseUrl: String = BuildConfig.API_BASE_URL

    /**
     * PKCE callback the authorize page redirects to.
     *
     * ⛔ MUST MATCH THE SERVER'S `NATIVE_REDIRECT_ALLOWLIST` (its native OAuth
     * module) BYTE FOR BYTE. That
     * allowlist is a literal-equality check with an explicit note never to relax
     * it to a prefix or regex, because `redirect_uri` arrives on a URL a signed-in
     * user's browser is following — an unchecked value would hand an authorization
     * code to a host an attacker controls. A mismatch here fails closed as an
     * opaque `invalid_grant`, which deliberately looks identical to a replayed
     * code, so it will not tell you what is wrong.
     *
     * The custom scheme is used rather than the HTTPS App Link because the App
     * Link cannot be verified until the app is signed by Play (verification needs
     * the app signing certificate, which Play holds and only issues after the
     * first upload). Both forms are already allowlisted server-side, so switching
     * later needs no server change.
     */
    const val PKCE_REDIRECT_URI: String = "districtai://auth"
}
