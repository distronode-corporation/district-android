package com.distronode.districtai.ui

import com.distronode.districtai.core.designsystem.Tone

/**
 * Which [Tone] a call's status wears.
 *
 * ⛔ THIS EXISTS SO STATE IS SCANNABLE WITHOUT READING. Every status used to render as the same
 * grey body text, so a `completed` call and a `failed` one were typographically identical — the
 * only difference was reading the word, at the far right of a 1920px row. A badge with a tone
 * means the eye sorts the list before the reader does.
 *
 * ⛔ SHARED BY THE OVERVIEW AND THE CALL LOG, DELIBERATELY. Both render the same statuses, and
 * two copies of this `when` would drift — one of them would learn about a new status and the
 * other would keep painting it neutral. It lives in `ui/` rather than in either screen's package
 * for that reason.
 *
 * ⚠️ FAILS TO [Tone.Neutral], NOT TO A GUESS. The server's status column is a plain string with
 * no enum behind it, so an unmodelled value is a real possibility. Painting an unknown status
 * green or red would assert something about a call this client does not understand; neutral says
 * "this is a status, and I am not interpreting it".
 *
 * ⚠️ Lowercased before matching. Nothing normalises the column on write, and the tier column in
 * the same database is verified mixed-case (`VoicePro`), so assuming lowercase here is exactly
 * the bug that makes a comparison silently never match.
 */
fun toneForCallStatus(status: String?): Tone = when (status?.trim()?.lowercase()) {
    // Terminal and fine.
    "completed", "complete", "answered" -> Tone.Success

    // Terminal and not fine. `busy` and `no-answer` are Twilio's spellings; `failed` and
    // `canceled` are the provider-agnostic ones the route can also emit.
    "failed", "busy", "no-answer", "noanswer", "canceled", "cancelled" -> Tone.Danger

    // Still happening. The accent tone rather than a semantic one: in progress is not a verdict.
    "in-progress", "in_progress", "inprogress", "ringing", "queued", "initiated" -> Tone.District

    // Includes null. See the ⚠️ above: unknown is not a verdict either.
    else -> Tone.Neutral
}
