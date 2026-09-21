package com.distronode.districtai.core.model

/**
 * The ONE place a room name is constructed.
 *
 * ⛔ WHY THIS IS A FUNCTION AND NOT A STRING TEMPLATE AT THE CALL SITE. `/api/district/calls/token`
 * accepts `meet_` and `video_` through the same `startsWith` chain and the same regex, and treats
 * both as standalone rooms — so the two prefixes are ONE CHARACTER APART and both are accepted.
 * They are not equivalent: a `video_` room is a 1:1 AI-avatar call that starts a **billable Tavus
 * avatar session**, while `meet_` is the free multi-party room this app joins. Nothing server-side
 * would refuse a `video_` name minted by mistake; it would simply work, and bill. Centralising the
 * construction means there is exactly one line to review and exactly one test to pin, and
 * `MeetRoomNameTest` asserts both that the prefix is `meet_` and that no input can produce a
 * `video_` name.
 *
 * ⛔ THE CLIENT MINTS THE NAME, WHICH IS UNUSUAL FOR THIS API AND IS NOT A MISTAKE. The web does
 * the same thing (the web's active-room page builds `meet_${workspaceId}_${name}`),
 * and the server parses the workspace id back OUT of the name to decide who may join — so the name
 * is not an authorization claim: `requireWorkspaceRole` still runs against the id it extracted, and
 * naming another tenant's workspace produces a 403 rather than access to it.
 *
 * ⛔ AND THE NAME IS NOT WHERE A ROOM'S SECRECY LIVES. Suffixes are human-typed and low entropy
 * ("standup"), so anyone who can guess a workspace id and a plausible name has guessed a valid
 * room name. That is why an unauthenticated guest needs a signed invite instead — see
 * [RoomTokenResponse.guestInvite]. Do not treat an unguessable suffix as a security measure; it
 * is not one, and choosing one would imply it was.
 */
object MeetRoomName {

    /**
     * The multi-party room prefix. ⛔ There is deliberately no constant for `video_` anywhere in
     * this client: the avatar surface is not built here, and a named constant is the first step to
     * a call site that picks between them.
     */
    const val MEET_PREFIX: String = "meet_"

    /**
     * The character set the server's own regex admits in a workspace id, and the set the web's
     * lobby restricts a typed suffix to.
     *
     * ⚠️ THE SERVER'S REGEX IS `^(meet|video)_([a-zA-Z0-9-]+)_(.+)$`, so the SUFFIX half is
     * genuinely unrestricted — `.+` matches anything, underscores included. This client normalises
     * anyway, deliberately: an underscore in the suffix makes the name ambiguous to any reader
     * splitting on `_`, and a space or a slash makes it awkward in the guest URL the server builds
     * from it. Normalising here is a compatibility choice with the web lobby, not an enforcement
     * of a server rule.
     */
    private val ALLOWED = Regex("[^a-zA-Z0-9-]")

    /**
     * Build the room name for a workspace and a human-typed suffix.
     *
     * @return null when [suffix] normalises to nothing. ⛔ NULL RATHER THAN A NAME WITH AN EMPTY
     *   TAIL: `meet_<ws>_` matches the server's regex only if the `.+` group is non-empty, so an
     *   empty suffix would produce a 400 "Invalid meeting room format" that reads as a server
     *   fault. Refusing here lets the screen say "name your room" instead.
     */
    fun of(workspaceId: String, suffix: String): String? {
        val normalized = normalizeSuffix(suffix)
        if (normalized.isEmpty() || workspaceId.isBlank()) return null
        return "$MEET_PREFIX$workspaceId${"_"}$normalized"
    }

    /**
     * Lower-case, hyphen-separated, and stripped of everything else.
     *
     * ⚠️ Exposed so a lobby can show the user what its input will become BEFORE they join. A field
     * that silently rewrote "Weekly Review" into "weekly-review" at submit time would leave
     * someone unable to explain why their meeting is not the one their colleague joined.
     */
    fun normalizeSuffix(suffix: String): String =
        ALLOWED.replace(suffix.trim().replace(' ', '-'), "").trim('-').lowercase()

    /**
     * ⚠️ Used to decide whether a stored `Meeting.roomName` belongs to this surface at all. The
     * Meeting table records whatever room the Companion was in, so this is a filter rather than an
     * assertion — a row that fails it is another product's, not corrupt data.
     */
    fun isMeetRoom(roomName: String): Boolean = roomName.startsWith(MEET_PREFIX)

    /**
     * The human half of a room name, for display.
     *
     * ⚠️ SPLITS FROM THE LEFT ON EXACTLY TWO SEPARATORS, because the suffix may legitimately
     * contain further underscores when the name came from somewhere other than [of] (the server
     * accepts `.+` there). `substringAfter` twice would be equivalent; `split(limit = 3)` says the
     * intent. Returns the whole name unchanged when it does not have the shape, so a display never
     * silently blanks.
     */
    fun displayName(roomName: String): String {
        val parts = roomName.split("_", limit = NAME_PARTS)
        return if (parts.size == NAME_PARTS && parts[0] == "meet" && parts[2].isNotEmpty()) {
            parts[2]
        } else {
            roomName
        }
    }

    /** prefix, workspace id, suffix — the three groups the server's own regex captures. */
    private const val NAME_PARTS = 3
}
