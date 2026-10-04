package com.distronode.districtai.core.network

/**
 * The two District roles that can appear as a bar on a scheduling admin op.
 *
 * ⚠️ `agency` IS ABSENT AND THAT IS NOT AN OMISSION. It clears both bars, so it is never the
 * MINIMUM for anything; the server's `roleClears` says the same in the other direction. A third
 * entry here would be a value no op could hold.
 */
enum class SchedulingAdminRole(val wire: String) {
    VIEWER("viewer"),
    CLIENT("client"),
}

/**
 * Every operation the native scheduling admin may ask `POST /api/district/scheduling/admin` to
 * perform, named once.
 *
 * ⛔ AN ALLOWLIST, NOT A PROXY, AND [wire] IS THE WHOLE ADDRESS. The route takes an `op` NAME and
 * never a path, so a caller cannot reach a scheduler route the server's catalog does not name.
 * The server's op catalog (`admin-ops.ts`) records what that excludes and why:
 * `/v1/settings/{google,email,zoom,livekit,stripe,tracking}` hold INSTANCE credentials shared by
 * every tenancy, `/v1/platform/…` can create and delete any tenancy at all, and
 * `PATCH /v1/users/{id}/role` plus `POST /v1/users/{id}/transfer-ownership` would let a member
 * re-arrange who owns the tenancy behind District's back. This enum is the Android half of that
 * closure.
 *
 * ⚠️ [wire] IS THE SERVER'S KEY VERBATIM, DOTS AND ALL, and the entry names are its
 * SCREAMING_SNAKE transliteration. A key renamed on the server is a **400 `unknown_op`** here, not
 * a compile error, because the op crosses the wire as a string — so `SchedulingAdminOpTest`
 * embeds all 64 strings a second time rather than deriving them from [entries]. A test that
 * re-read this enum would assert that the code equals itself and would pass through any rename.
 *
 * ⛔ [minRole] AND [isWrite] ARE CONSTRUCTOR ARGUMENTS RATHER THAN TWO EXHAUSTIVE `when`
 * EXPRESSIONS, WHICH IS A DELIBERATE DIVERGENCE FROM THE iOS CLIENT AND IS STRICTLY STRONGER. Its
 * `switch` has no `default` precisely so an unclassified case fails to compile; here an entry
 * cannot be ADDED without classifying it, which is the same guarantee with no second list to keep
 * in step.
 *
 * ⛔ THIS IS A NAME, NOT A REQUEST. Nothing here carries the scheduler path, the HTTP verb or the
 * params schema: all three live on the server, which is what makes the catalog a boundary rather
 * than a duplicated client.
 *
 * @property minRole the lowest District role the server will accept this op from.
 *
 *   ⛔ THE RULE IS "READS ARE `viewer`, WRITES ARE `client`" WITH ONE EXCEPTION, AND THE EXCEPTION
 *   IS NOT A RELAXATION. The `me.*` and `calendar.*` namespaces are `viewer` even when they WRITE,
 *   because they touch only the caller's own scheduler user: their display name, timezone,
 *   notification preferences, avatar and calendar connections. A viewer who cannot set their own
 *   timezone is offered every booking window in the wrong hours, and a viewer who cannot connect
 *   their own calendar cannot be booked at all.
 *
 *   ⛔ IT IS THE WEAKER HALF OF THE GATE AND MUST NOT BE READ AS THE ANSWER. The scheduler
 *   enforces its own `requireAdmin` on the settings and reassign routes, and its own
 *   host-ownership checks on the booking routes — against the MEMBER's key. A District `viewer`
 *   calling a read this property allows can still come back **403**. What it is for is deciding
 *   whether to draw a control and whether to spend a request.
 *
 * @property isWrite whether sending this op spends a write from the workspace's hourly budget.
 *
 *   ⛔ "NOT A GET", WHICH IS THE SERVER'S OWN TEST AND NOT A SEPARATE OPINION. The op route budgets
 *   `opSendsBody(op) || op.method === "DELETE"`, and `opSendsBody` is
 *   `method !== "GET" && method !== "DELETE"` — so the union is exactly "the method is not GET".
 *   Twenty-three reads, forty-one writes.
 *
 *   ⚠️ IT IS NOT `minRole == CLIENT`, AND THE TWO DISAGREE ON SIX OPS. The viewer-level writes
 *   above are billed to a SEPARATE 30-per-member-per-hour bucket rather than the workspace's 120,
 *   precisely so one viewer changing their avatar in a loop cannot lock every administrator out
 *   of writes for an hour. Deriving this from the role would put them back in the shared pool on
 *   the client's side of the wire.
 */
enum class SchedulingAdminOp(
    val wire: String,
    val minRole: SchedulingAdminRole,
    val isWrite: Boolean,
) {
    // ── Self: viewer-level throughout, including its two writes ──────────────────────────────
    ME_GET("me.get", SchedulingAdminRole.VIEWER, isWrite = false),
    ME_PATCH("me.patch", SchedulingAdminRole.VIEWER, isWrite = true),
    ME_AVATAR_DELETE("me.avatar.delete", SchedulingAdminRole.VIEWER, isWrite = true),

    // ── Event types ─────────────────────────────────────────────────────────────────────────
    EVENT_TYPES_LIST("eventTypes.list", SchedulingAdminRole.VIEWER, isWrite = false),
    EVENT_TYPES_GET("eventTypes.get", SchedulingAdminRole.VIEWER, isWrite = false),
    EVENT_TYPES_CREATE("eventTypes.create", SchedulingAdminRole.CLIENT, isWrite = true),
    EVENT_TYPES_PATCH("eventTypes.patch", SchedulingAdminRole.CLIENT, isWrite = true),
    EVENT_TYPES_DELETE("eventTypes.delete", SchedulingAdminRole.CLIENT, isWrite = true),
    EVENT_TYPES_HOSTS_GET("eventTypes.hosts.get", SchedulingAdminRole.VIEWER, isWrite = false),
    EVENT_TYPES_HOSTS_PUT("eventTypes.hosts.put", SchedulingAdminRole.CLIENT, isWrite = true),
    EVENT_TYPES_TEST_EMAIL("eventTypes.testEmail", SchedulingAdminRole.CLIENT, isWrite = true),
    EVENT_TYPES_QUESTIONS_LIST(
        "eventTypes.questions.list",
        SchedulingAdminRole.VIEWER,
        isWrite = false,
    ),
    EVENT_TYPES_QUESTIONS_CREATE(
        "eventTypes.questions.create",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    EVENT_TYPES_QUESTIONS_PATCH(
        "eventTypes.questions.patch",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    EVENT_TYPES_QUESTIONS_DELETE(
        "eventTypes.questions.delete",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    EVENT_TYPES_SLOTS("eventTypes.slots", SchedulingAdminRole.VIEWER, isWrite = false),

    // ── Availability: weekly rules, and dated overrides ──────────────────────────────────────
    AVAILABILITY_RULES_LIST("availability.rules.list", SchedulingAdminRole.VIEWER, isWrite = false),
    AVAILABILITY_RULES_CREATE(
        "availability.rules.create",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    AVAILABILITY_RULES_PATCH("availability.rules.patch", SchedulingAdminRole.CLIENT, isWrite = true),
    AVAILABILITY_RULES_DELETE(
        "availability.rules.delete",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    AVAILABILITY_OVERRIDES_LIST(
        "availability.overrides.list",
        SchedulingAdminRole.VIEWER,
        isWrite = false,
    ),
    AVAILABILITY_OVERRIDES_CREATE(
        "availability.overrides.create",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    AVAILABILITY_OVERRIDES_PATCH(
        "availability.overrides.patch",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    AVAILABILITY_OVERRIDES_DELETE(
        "availability.overrides.delete",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    AVAILABILITY_OVERRIDES_DELETE_GROUP(
        "availability.overrides.deleteGroup",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),

    // ── Bookings ────────────────────────────────────────────────────────────────────────────
    BOOKINGS_LIST("bookings.list", SchedulingAdminRole.VIEWER, isWrite = false),
    BOOKINGS_ANSWERS("bookings.answers", SchedulingAdminRole.VIEWER, isWrite = false),
    BOOKINGS_CANCEL("bookings.cancel", SchedulingAdminRole.CLIENT, isWrite = true),
    BOOKINGS_RESCHEDULE("bookings.reschedule", SchedulingAdminRole.CLIENT, isWrite = true),
    BOOKINGS_REASSIGN("bookings.reassign", SchedulingAdminRole.CLIENT, isWrite = true),

    // ── The caller's own calendars: viewer-level throughout, writes included ─────────────────
    CALENDAR_STATUS("calendar.status", SchedulingAdminRole.VIEWER, isWrite = false),
    CALENDAR_CALDAV_CONNECT("calendar.caldav.connect", SchedulingAdminRole.VIEWER, isWrite = true),
    CALENDAR_CONNECTIONS_CALENDARS_GET(
        "calendar.connections.calendars.get",
        SchedulingAdminRole.VIEWER,
        isWrite = false,
    ),
    CALENDAR_CONNECTIONS_CALENDARS_PUT(
        "calendar.connections.calendars.put",
        SchedulingAdminRole.VIEWER,
        isWrite = true,
    ),
    CALENDAR_CONNECTIONS_DESTINATION(
        "calendar.connections.destination",
        SchedulingAdminRole.VIEWER,
        isWrite = true,
    ),
    CALENDAR_CONNECTIONS_DELETE(
        "calendar.connections.delete",
        SchedulingAdminRole.VIEWER,
        isWrite = true,
    ),
    ZOOM_STATUS("zoom.status", SchedulingAdminRole.VIEWER, isWrite = false),

    // ── The tenancy's scheduler users ───────────────────────────────────────────────────────
    USERS_LIST("users.list", SchedulingAdminRole.VIEWER, isWrite = false),
    USERS_ARCHIVE("users.archive", SchedulingAdminRole.CLIENT, isWrite = true),
    USERS_UPCOMING_BOOKINGS("users.upcomingBookings", SchedulingAdminRole.VIEWER, isWrite = false),

    // ── Teams ───────────────────────────────────────────────────────────────────────────────
    TEAMS_LIST("teams.list", SchedulingAdminRole.VIEWER, isWrite = false),
    TEAMS_GET("teams.get", SchedulingAdminRole.VIEWER, isWrite = false),
    TEAMS_CREATE("teams.create", SchedulingAdminRole.CLIENT, isWrite = true),
    TEAMS_PATCH("teams.patch", SchedulingAdminRole.CLIENT, isWrite = true),
    TEAMS_DELETE("teams.delete", SchedulingAdminRole.CLIENT, isWrite = true),
    TEAMS_MEMBERS_ADD("teams.members.add", SchedulingAdminRole.CLIENT, isWrite = true),
    TEAMS_MEMBERS_PATCH("teams.members.patch", SchedulingAdminRole.CLIENT, isWrite = true),
    TEAMS_MEMBERS_REMOVE("teams.members.remove", SchedulingAdminRole.CLIENT, isWrite = true),

    // ── Settings ────────────────────────────────────────────────────────────────────────────
    SETTINGS_BRANDING_GET("settings.branding.get", SchedulingAdminRole.VIEWER, isWrite = false),
    SETTINGS_BRANDING_PATCH("settings.branding.patch", SchedulingAdminRole.CLIENT, isWrite = true),
    SETTINGS_BRANDING_LOGO_DELETE(
        "settings.branding.logo.delete",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    SETTINGS_BRANDING_BANNER_DELETE(
        "settings.branding.banner.delete",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    SETTINGS_LLM_GET("settings.llm.get", SchedulingAdminRole.VIEWER, isWrite = false),
    SETTINGS_LLM_PATCH("settings.llm.patch", SchedulingAdminRole.CLIENT, isWrite = true),

    // ── Integrations the tenancy owns ───────────────────────────────────────────────────────
    API_KEYS_LIST("apiKeys.list", SchedulingAdminRole.VIEWER, isWrite = false),
    API_KEYS_CREATE("apiKeys.create", SchedulingAdminRole.CLIENT, isWrite = true),
    API_KEYS_DELETE("apiKeys.delete", SchedulingAdminRole.CLIENT, isWrite = true),
    OAUTH_CONNECTIONS_LIST("oauth.connections.list", SchedulingAdminRole.VIEWER, isWrite = false),
    OAUTH_CONNECTIONS_DELETE(
        "oauth.connections.delete",
        SchedulingAdminRole.CLIENT,
        isWrite = true,
    ),
    WEBHOOKS_LIST("webhooks.list", SchedulingAdminRole.VIEWER, isWrite = false),
    WEBHOOKS_CREATE("webhooks.create", SchedulingAdminRole.CLIENT, isWrite = true),
    WEBHOOKS_PATCH("webhooks.patch", SchedulingAdminRole.CLIENT, isWrite = true),
    WEBHOOKS_DELETE("webhooks.delete", SchedulingAdminRole.CLIENT, isWrite = true),
    WEBHOOKS_DELIVERIES("webhooks.deliveries", SchedulingAdminRole.VIEWER, isWrite = false),
}

/**
 * Which image an upload replaces.
 *
 * ⛔ A CLOSED SET, BECAUSE AN UNKNOWN `target` IS A **400 `unknown_target`** AND THE THREE DO NOT
 * SHARE A PERMISSION. `logo` and `banner` are the WORKSPACE's public booking-page branding and are
 * `client`; `avatar` is the caller's own picture and is `viewer`, so it spends the per-member
 * budget rather than the workspace's. Spelling the target as a `String` would let a screen offer a
 * control the server refuses and would put an avatar on the branding budget.
 */
enum class SchedulingAdminUploadTarget(val wire: String, val minRole: SchedulingAdminRole) {
    LOGO("logo", SchedulingAdminRole.CLIENT),
    BANNER("banner", SchedulingAdminRole.CLIENT),
    AVATAR("avatar", SchedulingAdminRole.VIEWER),
}
