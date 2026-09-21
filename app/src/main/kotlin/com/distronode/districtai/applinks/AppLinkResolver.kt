package com.distronode.districtai.applinks

/**
 * The hosts whose `https://` links this app claims. Must stay in step with the `autoVerify`
 * intent filter in AndroidManifest.xml AND with the statement list the server publishes at
 * `/.well-known/assetlinks.json`.
 *
 * ⛔ THREE PLACES, AND ALL THREE HAVE TO AGREE FOR A LINK TO OPEN THE APP: the manifest filter
 * (what the OS offers us), the statement list (what the OS will believe), and this set (what the
 * app will act on). A host present in only some of them fails differently in each direction — a
 * manifest host with no statement never verifies, and a statement with no manifest host is dead
 * config. `distronode.ca` is in none of them on purpose; see the manifest.
 */
internal val APP_LINK_HOSTS = setOf("distronode.com", "www.distronode.com")

/** The claimed path prefix. ⛔ Widening this is a promise to handle more URLs — see the manifest. */
internal const val APP_LINK_PATH_PREFIX = "/dashboard/district"

/**
 * A section of the dashboard that this app has a real destination for.
 *
 * ⚠️ THIS IS NOT A COPY OF THE WEB'S ROUTE LIST AND MUST NOT BECOME ONE. The web dashboard has
 * 21 top-level pages under `/dashboard/district` (the root plus 20 first segments); this enum holds
 * the twelve whose mapping onto an Android destination is UNAMBIGUOUS. The other nine are listed
 * in [appLinkDestination]'s KDoc with the reason each one is not here, because "no mapping yet"
 * and "no unambiguous mapping" are different states and the second one is a decision rather than
 * a gap.
 *
 * ⛔ COUNT PAGES, NOT SEGMENTS. `calls/<id>` is a deeper SEGMENT rather than a page and belongs to
 * neither count. Every page has to appear in exactly one half (this enum or the list in
 * [appLinkDestination]); a page named in neither is an oversight, not a decision.
 *
 * ⚠️ PUBLIC WHILE THE REST OF THIS FILE IS `internal`, AND THAT IS DELIBERATE. [AppLinkDeepLinks]
 * mirrors `PushDeepLinks`, which is a public holder carrying a public payload (`InboxDeepLink`);
 * a public holder cannot expose an `internal` type argument, so this enum has to match its
 * counterpart's visibility. The resolver's own vocabulary — [AppLinkDestination],
 * [appLinkDestination], the host and prefix constants — stays `internal`, because none of it
 * crosses the holder.
 */
enum class DistrictSection {
    OVERVIEW,
    INBOX,
    CALLS,
    CONTACTS,
    HQ,
    ANALYTICS,
    BILLING,
    MARKETPLACE,
    WORKFLOWS,

    /**
     * ⚠️ THE WORKSPACE-SCOPED ENTRY WHOSE ANDROID ROUTE CARRIES NO ROLE, which is why it is worth
     * a note here rather than sitting silently in the list. Every other workspace-scoped section
     * resolves to a route with a `{role}` segment; `Routes.SCHEDULING` deliberately has none,
     * because the status route sends `canManage` with every read. So the cross-tenant hazard iOS
     * records in `AppLinkRouting` — a link naming workspace B paired with the role the user holds
     * in workspace A — cannot arise for this one at all.
     */
    SCHEDULING,

    /**
     * District Desk: the tenant's OWN customers' tickets.
     *
     * ⛔ NOT [SUPPORT], AND THE TWO MUST NEVER BE COLLAPSED. `/dashboard/district/desk` is the
     * tenant's customers writing to THEM; `/dashboard/district/support` is the tenant writing to
     * US. They are one segment apart on the web and one enum entry apart here, and a link that
     * resolved to the wrong one would open a stranger's correspondence under the heading the
     * operator expected.
     *
     * ⚠️ ITS ANDROID DESTINATION CARRIES A ROLE, and the role GATES THE READ rather than one
     * control — every desk route excludes `viewer`. So the cross-tenant hazard that [appLinkRoute]
     * is careful about (a link naming workspace B paired with the role held in workspace A) fails
     * in the safe direction here: a viewer-in-B who holds agency in A reaches a screen whose every
     * request the server refuses, and sees the refusal rather than another tenant's data.
     */
    DESK,

    /**
     * Support: this workspace's own requests with Distronode.
     *
     * ⛔ NOT [DESK]. See the ⛔ there.
     */
    SUPPORT,
}

/**
 * What to do with an `ACTION_VIEW` intent.
 *
 * ⛔ THREE OUTCOMES, AND THE THIRD IS NOT THE SAME AS THE SECOND. [Ignore] means "this intent is
 * not an app link at all" — the login callback's `districtai://auth` URI arrives as an ACTION_VIEW
 * too, and handing that to a browser would break sign-in. [OpenInBrowser] means "this IS one of our
 * URLs, and the honest answer is the web page". Collapsing them would make the login callback
 * open in a Custom Tab.
 */
internal sealed interface AppLinkDestination {

    /** Navigate in-app, once there is somewhere to navigate to. */
    data class Section(val section: DistrictSection) : AppLinkDestination

    /**
     * Hand it to the browser through the existing Custom Tabs seam.
     *
     * ⛔ NEVER A DEAD END, WHICH IS THE WHOLE REASON THIS CASE EXISTS. A `pathPrefix` claim is
     * broad, and an app that claims a URL and then shows nothing has taken the user somewhere they
     * cannot leave. Anything on our hosts that this app has no screen for goes to the real web
     * page, which does.
     */
    data object OpenInBrowser : AppLinkDestination

    /** Not ours. Leave the intent completely alone so whatever else owns it can have it. */
    data object Ignore : AppLinkDestination
}

/**
 * Resolve a claimed URL to a destination.
 *
 * ⛔ TAKES TWO STRINGS RATHER THAN A `Uri`, WHICH IS WHAT MAKES IT TESTABLE AT ALL. `android.net.Uri`
 * is a framework class: in a plain JVM unit test its statics are stubbed to return null (this
 * module sets `isReturnDefaultValues = true`), so a function taking a `Uri` could only be exercised
 * under Robolectric. This is the same shape as `pushIntentAction`, which takes four nullable
 * strings for the same reason — the Activity does the extracting, and the decision is pure.
 *
 * ⚠️ BOTH ARGUMENTS ARE NULLABLE BECAUSE A REAL INTENT'S DATA CAN BE. `Uri.getHost()` is null for
 * an opaque URI and `getPath()` is null for `https://host` with no path at all; both arrive here
 * rather than being pre-validated, so the null-handling is asserted rather than assumed.
 *
 * ## The seven web pages deliberately NOT mapped
 *
 * All seven are inside the claimed prefix, so none of them reaches a browser, and each is absent
 * from [DistrictSection] for a stated reason rather than by oversight. One DEEPER SEGMENT is
 * described alongside them and is not a page.
 *
 * ⚠️ ALL SEVEN LAND ON THE OVERVIEW; THE DEEPER SEGMENT LANDS ON ITS PARENT SECTION. Only the
 * FIRST segment after the prefix decides (see [firstSegmentAfterPrefix]), so `calls/<id>` resolves
 * to [DistrictSection.CALLS], which is the better behaviour: the call log is one tap from the call
 * the link named and the overview is not. A deeper path under an unmapped page, such as
 * `meetings/<id>`, lands where its first segment does: on the overview. `AppLinkResolverTest`
 * pins both halves.
 *
 * ⚠️ COUNTS HERE ARE EASY TO GET WRONG. Count the enum and the bullets against the real page list
 * before trusting any number in this KDoc.
 *
 * - `settings` — ⛔ AMBIGUOUS IN A WAY THAT MATTERS. Android has TWO settings destinations,
 *   `Routes.SETTINGS` (account: sign-out, account deletion, deliberately workspace-free) and
 *   `Routes.WORKSPACE_SETTINGS` (tenant: persona, routing, members, and viewer-excluded on every
 *   route behind it). The web has one page. Guessing wrong sends a user to a screen that either
 *   403s or has nothing they were looking for.
 * - `workspaces` — the nearest Android surface is the members section *under* workspace settings,
 *   which is a roster editor rather than a workspace list/switcher. Different thing.
 * - `rooms` and `meetings` — two web routes, one Android destination (`Routes.ROOMS`), so neither
 *   claim is unambiguous. `meetings/<id>` is worse: `Routes.ACTIVE_ROOM` needs the full
 *   `meet_<workspaceId>_<suffix>` room name, which is what a media token is minted against, and
 *   reconstructing one from a URL segment is exactly how a `video_` name (a billable avatar
 *   session, one character away) gets built by mistake. `MeetRoomName` is the only mint.
 * - `live` — Android's `Routes.DIALER` is the OUTBOUND softphone, not a live-calls monitor.
 * - `campaigns`: "Campaigns" is not an entity in the data model (one boolean, one int and one
 *   string on Workspace); the workflows screen shows an SDR status card, which is not that page.
 * - `dgi` — DGI on Android is a per-contact enrichment surface behind a default-off flag, not a
 *   standalone screen.
 * - `calls/<id>` and any other deeper segment — the web publishes no such route today, and a
 *   detail id guessed out of a URL is a 404 with a back button that returns to the same 404.
 *   **Lands on the call log**, not the overview.
 *
 * `support` and `desk` are NOT here: they have real destinations, [DistrictSection.DESK] and
 * [DistrictSection.SUPPORT].
 *
 * ⚠️ ADDING ONE MEANS ADDING ITS ROUTE TO [appLinkRoute] IN THE SAME CHANGE. The `when` there is
 * exhaustive over the enum, so a new entry is a compile error rather than a silent fall-through to
 * the overview — which is the failure this shape exists to prevent.
 */
internal fun appLinkDestination(host: String?, path: String?): AppLinkDestination {
    if (host == null || host !in APP_LINK_HOSTS) return AppLinkDestination.Ignore
    if (path == null) return AppLinkDestination.OpenInBrowser
    if (!isClaimedPath(path)) return AppLinkDestination.OpenInBrowser

    return AppLinkDestination.Section(sectionFor(firstSegmentAfterPrefix(path)))
}

/**
 * ⛔ A PREFIX MATCH IS NOT A `startsWith`, AND THE DIFFERENCE IS A REAL URL. `startsWith` alone
 * would claim `/dashboard/districts-of-europe` and `/dashboard/districtai-pricing` — different
 * pages that merely begin with the same characters. The OS's own `pathPrefix` matching has the
 * same property, so the manifest filter WILL deliver those here; this is where they are rejected
 * and sent to the browser, which is the correct owner of a marketing page.
 */
private fun isClaimedPath(path: String): Boolean =
    path == APP_LINK_PATH_PREFIX ||
        path.startsWith("$APP_LINK_PATH_PREFIX/")

/**
 * The one path segment that decides the section, or `""` for the dashboard root.
 *
 * ⚠️ TRAILING SLASHES AND EMPTY SEGMENTS COLLAPSE TO THE ROOT. `/dashboard/district`,
 * `/dashboard/district/` and `/dashboard/district//` are the same page to the web server, so they
 * have to be the same destination here — otherwise a link copied with a trailing slash behaves
 * differently from the same link without one, which is indistinguishable from a bug.
 */
private fun firstSegmentAfterPrefix(path: String): String =
    path.removePrefix(APP_LINK_PATH_PREFIX)
        .split('/')
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

/**
 * ⚠️ AN UNRECOGNISED SEGMENT IS THE OVERVIEW, NOT THE BROWSER, AND THAT IS THE DELIBERATE CALL FOR
 * ANYTHING INSIDE THE CLAIMED PREFIX. Having claimed the URL, bouncing it back out to a Custom Tab
 * is the worst of both worlds: the user waited for an app to open and got a browser anyway. The
 * overview is a real, useful, signed-in landing, and it is where all nine unmapped pages listed in
 * [appLinkDestination] land. The deeper segment listed there never reaches this fallback:
 * `calls/<id>` is matched by its FIRST segment, so it resolves to its parent section.
 *
 * ⚠️ CASE-FOLDED because a URL path is case-sensitive to a server but a shared link is retyped by
 * humans. `lowercase()` here can only ever widen what resolves to a real section; it cannot make a
 * claimed URL unreachable.
 */
private fun sectionFor(segment: String): DistrictSection = when (segment.lowercase()) {
    "inbox" -> DistrictSection.INBOX
    "calls" -> DistrictSection.CALLS
    "contacts" -> DistrictSection.CONTACTS
    "hq" -> DistrictSection.HQ
    "analytics" -> DistrictSection.ANALYTICS
    "billing" -> DistrictSection.BILLING
    "marketplace" -> DistrictSection.MARKETPLACE
    "workflows" -> DistrictSection.WORKFLOWS
    "scheduling" -> DistrictSection.SCHEDULING
    // ⛔ ONE SEGMENT APART AND TWO DIFFERENT PRODUCTS. See the ⛔ on DistrictSection.DESK.
    "desk" -> DistrictSection.DESK
    "support" -> DistrictSection.SUPPORT
    else -> DistrictSection.OVERVIEW
}
