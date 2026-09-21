package com.distronode.districtai.applinks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an `ACTION_VIEW` intent resolves to.
 *
 * ⛔ PLAIN JVM, NO ROBOLECTRIC, AND THAT IS THE PROPERTY THE RESOLVER WAS SHAPED TO HAVE.
 * [appLinkDestination] takes two strings rather than a `Uri` precisely so it can be exercised here —
 * `android.net.Uri`'s statics return null under `isReturnDefaultValues = true`, so a `Uri`-taking
 * signature would have forced every one of these cases onto the slow runner. The same split
 * `PushIntentActionTest` (bare JVM) and `PushIntentsTest` (Robolectric) already make.
 *
 * ⛔ THE THREE OUTCOMES ARE NOT INTERCHANGEABLE AND MOST OF THIS FILE IS ABOUT KEEPING THEM APART.
 * `Ignore` means "not ours, do not touch the intent" — the `districtai://auth` login callback
 * arrives as an ACTION_VIEW too, and handing that to a Custom Tab would break sign-in.
 * `OpenInBrowser` means "ours, but the honest answer is the web page". Collapsing either into the
 * other is a live bug rather than a tidy-up.
 */
class AppLinkResolverTest {

    private fun destination(host: String?, path: String?) = appLinkDestination(host, path)

    private fun section(path: String) =
        destination("www.distronode.com", path) as AppLinkDestination.Section

    // ── Which hosts are ours ──────────────────────────────────────────────────────────────

    @Test
    fun `both claimed hosts resolve`() {
        // ⛔ THIS SET MUST AGREE WITH THE MANIFEST FILTER AND THE PUBLISHED STATEMENT LIST. A host in
        // only two of the three fails differently in each direction, and nothing in the build checks
        // the agreement — the manifest declares exactly these two with `autoVerify="true"`.
        assertEquals(setOf("distronode.com", "www.distronode.com"), APP_LINK_HOSTS)
        assertEquals(
            AppLinkDestination.Section(DistrictSection.OVERVIEW),
            destination("distronode.com", APP_LINK_PATH_PREFIX),
        )
        assertEquals(
            AppLinkDestination.Section(DistrictSection.OVERVIEW),
            destination("www.distronode.com", APP_LINK_PATH_PREFIX),
        )
    }

    @Test
    fun `a null host is ignored rather than opened`() {
        // ⚠️ `Uri.getHost()` IS NULL FOR AN OPAQUE URI, which is exactly what the login callback
        // `districtai://auth` is not — but a malformed intent can produce one, and the intent is
        // attacker-influenced. Ignore is the only safe answer: it leaves `intent.data` alone.
        assertEquals(AppLinkDestination.Ignore, destination(null, "/dashboard/district"))
    }

    @Test
    fun `the login callback is ignored, not handed to a browser`() {
        // ⛔ THE REGRESSION THIS WHOLE THIRD CASE EXISTS FOR. `districtai://auth` has host "auth".
        // If an unclaimed host resolved to OpenInBrowser, every native sign-in would end in a Custom
        // Tab showing a `districtai://` URL the browser cannot fetch, and the PKCE code would be
        // dropped on the floor.
        assertEquals(AppLinkDestination.Ignore, destination("auth", null))
    }

    @Test
    fun `the Canadian canonical host is deliberately not claimed`() {
        // ⚠️ NOT AN OVERSIGHT. `distronode.ca` is a separate registrable domain and would need its
        // own statement list; until it has one, claiming it in the app while the OS refuses to
        // verify it is the worst of both worlds.
        assertEquals(AppLinkDestination.Ignore, destination("distronode.ca", "/dashboard/district"))
        assertEquals(
            AppLinkDestination.Ignore,
            destination("www.distronode.ca", "/dashboard/district/inbox"),
        )
    }

    @Test
    fun `a lookalike host is ignored`() {
        // Substring-of-ours and ours-as-a-substring both have to miss: the check is set membership,
        // and this pins that it never becomes a `contains`.
        assertEquals(AppLinkDestination.Ignore, destination("distronode.com.evil.test", "/dashboard/district"))
        assertEquals(AppLinkDestination.Ignore, destination("notdistronode.com", "/dashboard/district"))
    }

    // ── Which paths are ours ──────────────────────────────────────────────────────────────

    @Test
    fun `a claimed host with no path at all goes to the browser`() {
        // ⚠️ `Uri.getPath()` IS NULL FOR `https://host` WITH NO PATH. It arrives here rather than
        // being pre-validated, so the null branch is asserted rather than assumed.
        assertEquals(AppLinkDestination.OpenInBrowser, destination("distronode.com", null))
    }

    @Test
    fun `our marketing pages go to the browser, not into the app`() {
        // ⛔ NEVER A DEAD END. The manifest claims `/dashboard/district` with a `pathPrefix`, which
        // is a broad claim; anything on our hosts this app has no screen for has to reach the real
        // web page, because an app that swallows a URL and shows nothing has taken the user
        // somewhere they cannot leave.
        listOf("/", "/pricing", "/support", "/dashboard", "/dashboard/account").forEach {
            assertEquals(it, AppLinkDestination.OpenInBrowser, destination("distronode.com", it))
        }
    }

    @Test
    fun `a prefix match is not a startsWith`() {
        // ⛔ THESE ARE REAL URLS THE OS WILL DELIVER. Android's own `pathPrefix` matching is a bare
        // `startsWith`, so `/dashboard/districts-of-europe` reaches this app; it is a different page
        // and the browser is its correct owner. A `startsWith`-only check here would open it in-app
        // and show the district overview instead.
        assertEquals(
            AppLinkDestination.OpenInBrowser,
            destination("distronode.com", "/dashboard/districts-of-europe"),
        )
        assertEquals(
            AppLinkDestination.OpenInBrowser,
            destination("distronode.com", "/dashboard/districtai-pricing"),
        )
    }

    // ── Which section a claimed path names ────────────────────────────────────────────────

    @Test
    fun `the twelve mapped segments each resolve to their own section`() {
        val expected = mapOf(
            "inbox" to DistrictSection.INBOX,
            "calls" to DistrictSection.CALLS,
            "contacts" to DistrictSection.CONTACTS,
            "hq" to DistrictSection.HQ,
            "analytics" to DistrictSection.ANALYTICS,
            "billing" to DistrictSection.BILLING,
            "marketplace" to DistrictSection.MARKETPLACE,
            "workflows" to DistrictSection.WORKFLOWS,
            "scheduling" to DistrictSection.SCHEDULING,
            // ⛔ THEY ARE ONE SEGMENT APART AND TWO DIFFERENT PRODUCTS: `desk` is the tenant's own
            // customers writing to THEM, `support` is the tenant writing to US. Mapping either to
            // the other's section would open a stranger's correspondence under the heading the
            // operator expected, which is why both are asserted here rather than one standing in
            // for the pair.
            "desk" to DistrictSection.DESK,
            "support" to DistrictSection.SUPPORT,
        )

        expected.forEach { (segment, districtSection) ->
            assertEquals(segment, districtSection, section("$APP_LINK_PATH_PREFIX/$segment").section)
        }
        // Every enum entry except the overview, which has no segment of its own, is reachable.
        assertEquals(DistrictSection.entries.size - 1, expected.values.toSet().size)
    }

    @Test
    fun `the prefix itself, a trailing slash and an empty segment are all the overview`() {
        // ⚠️ THE SAME PAGE TO THE WEB SERVER, so they have to be the same destination here. A link
        // copied with a trailing slash behaving differently from the same link without one is
        // indistinguishable from a bug to whoever pasted it.
        listOf(
            APP_LINK_PATH_PREFIX,
            "$APP_LINK_PATH_PREFIX/",
            "$APP_LINK_PATH_PREFIX//",
        ).forEach { assertEquals(it, DistrictSection.OVERVIEW, section(it).section) }
    }

    @Test
    fun `only the first segment after the prefix decides the section`() {
        // A deeper path is still its section: `/calls/CA123` is the call log, not the overview,
        // because the id is not something this app can navigate to (see the resolver's KDoc).
        assertEquals(DistrictSection.CALLS, section("$APP_LINK_PATH_PREFIX/calls/CA123").section)
        assertEquals(DistrictSection.INBOX, section("$APP_LINK_PATH_PREFIX/inbox/contact:c1").section)
    }

    @Test
    fun `the segment is case-folded`() {
        // ⚠️ A URL PATH IS CASE-SENSITIVE TO A SERVER BUT A SHARED LINK IS RETYPED BY HUMANS.
        // Folding can only widen what resolves to a real section; it cannot make a claimed URL
        // unreachable, because the fallback is the overview either way.
        assertEquals(DistrictSection.INBOX, section("$APP_LINK_PATH_PREFIX/Inbox").section)
        assertEquals(DistrictSection.BILLING, section("$APP_LINK_PATH_PREFIX/BILLING").section)
    }

    @Test
    fun `all seven unmapped web pages land on the overview`() {
        // ⛔ THIS LIST IS THE POINT OF THE RESOLVER'S KDOC AND IT IS A DECISION, NOT A GAP. Each of
        // these is inside the claimed prefix, so bouncing it back out to a Custom Tab would be the
        // worst outcome available: the user waited for an app to open and got a browser anyway. The
        // overview is a real signed-in landing.
        //
        // ⚠️ IF ONE OF THESE EVER GETS A DESTINATION, ITS ROW MOVES OUT OF HERE AND INTO
        // the mapped-sections test ABOVE, and the count assertion is what forces that edit to be
        // deliberate rather than silent.
        val unmapped = listOf(
            // Two Android settings destinations (account and workspace) against one web page.
            "settings",
            // The nearest Android surface is the members roster, which is a different thing.
            "workspaces",
            // Two web routes, one Android destination. `meetings/<id>` is worse: `Routes.ACTIVE_ROOM`
            // needs a minted `meet_` name, and reconstructing one from a URL segment is how a
            // billable `video_` name gets built by mistake.
            "rooms",
            "meetings",
            "meetings/abc123",
            // `Routes.DIALER` is the outbound softphone, not a live-calls monitor.
            "live",
            // "Campaigns" is not an entity in this app: one boolean, one int, one string.
            "campaigns",
            // A per-contact enrichment surface behind a default-off flag, not a screen.
            "dgi",
            // ⚠️ Every page under the claimed prefix belongs in exactly one of the two lists. A page
            // absent from both halves is an oversight, not a decision: nothing describes it and
            // nothing tests it.
        )

        unmapped.forEach {
            assertEquals(it, DistrictSection.OVERVIEW, section("$APP_LINK_PATH_PREFIX/$it").section)
        }
    }

    @Test
    fun `the deeper segment lands on its PARENT section, not on the overview`() {
        // ⛔ NOT EVERY DEEPER PATH RESOLVES TO THE OVERVIEW. Only the FIRST segment after the
        // prefix decides, so a deeper path inherits its parent's section: `calls/<id>` is the
        // call log.
        //
        // ⚠️ THIS IS THE BETTER BEHAVIOUR. The call log is one tap from the call the link named; the
        // overview is not. A KDoc that describes a stricter behaviour than the code has is the shape
        // that gets "restored" by a later reader.
        assertEquals(DistrictSection.CALLS, section("$APP_LINK_PATH_PREFIX/calls/CA123").section)
    }

    @Test
    fun `an unrecognised segment is the overview rather than the browser`() {
        assertEquals(DistrictSection.OVERVIEW, section("$APP_LINK_PATH_PREFIX/not-a-section").section)
        assertEquals(DistrictSection.OVERVIEW, section("$APP_LINK_PATH_PREFIX/inboxes").section)
    }
}
