package com.distronode.districtai.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message that has NOT been resolved to a String yet.
 *
 * ⛔ WHY A TYPE RATHER THAN A STRING. The code that decides what to say about a failure is a
 * ViewModel or a plain mapper — neither has a `Context`, so neither can call `getString`. The
 * previous shape forced those call sites to hold English literals, which is how
 * [FailureText]'s four messages and the overview's degraded-region sentence ended up as the
 * only untranslatable copy in an app whose manifest declares `supportsRtl="true"`. Deferring
 * resolution to the composable is what lets the wording live in `strings.xml` while the
 * DECISION about which wording applies stays where the failure is understood.
 *
 * ⚠️ TWO CASES, AND THE SPLIT IS THE INTERESTING PART. Some of what the user reads is ours and
 * must be translated; some of it is authored by the server and arrives per-response, so there
 * is no resource to point at. Keeping them distinct means "is this string translatable" is
 * answerable by reading the type rather than by guessing at the call site — and it makes the
 * places where server text reaches a user greppable, which is exactly the audit Fix 8's 5xx
 * leak needed.
 */
sealed interface UiText {

    /** Copy we own. Translatable, and the default for anything the client decides to say. */
    data class Resource(
        // ⚠️ `@get:` explicitly. A bare @StringRes on a constructor property currently annotates the
        // parameter only, and Kotlin warns that it will also start annotating the backing field —
        // naming the target keeps the meaning stable across that change.
        @get:StringRes val id: Int,
        /**
         * The single substitution for a `%1$s`-style resource, or null for a plain string.
         *
         * ⚠️ ONE ARGUMENT RATHER THAN A LIST, AND NOT A `vararg`. Passing a collection through to
         * `stringResource(id, vararg)` requires a spread operator, which copies the array on every
         * recomposition and is a detekt finding in a zero-tolerance configuration. Every
         * client-authored message that formats anything takes exactly one substitution, so the
         * general case is unbuilt rather than built and unused — widen it when a second argument
         * genuinely exists.
         *
         * ⚠️ `Any?` rather than an `Array`, also on purpose: this is a `data class` and tests assert
         * on equality, and an `Array` compares by identity so every such assertion would fail for
         * no visible reason.
         */
        val arg: Any? = null,
    ) : UiText

    /**
     * Text authored elsewhere — in practice the server's own error copy.
     *
     * ⛔ NOT A CONVENIENT ESCAPE HATCH. Every use is a place where untranslated text the client
     * did not write reaches a user, so it is only correct where the server's message is more
     * specific than anything we could say (a 409 "already exists", a 403 role refusal). It is
     * emphatically NOT correct for a 5xx, where the body can carry a Postgres or Prisma error
     * — see [toFailureText].
     */
    data class Literal(val value: String) : UiText

    /**
     * The literal text, or null for a resource reference.
     *
     * ⚠️ For assertions and diagnostics only. Rendering must go through [resolve] so a
     * [Resource] is never silently dropped.
     */
    val literalOrNull: String? get() = (this as? Literal)?.value

    /**
     * The resource id, or null for literal text.
     *
     * ⚠️ Also assertions and diagnostics only, and symmetric with [literalOrNull] on purpose. It
     * lets a ViewModel test assert WHICH message was chosen without needing a `Context` to render
     * it — the wording itself is asserted once, where the mapping lives.
     */
    @get:StringRes
    val resourceIdOrNull: Int? get() = (this as? Resource)?.id
}

/** Resolve to displayable text. The only place a [UiText] should become a `String`. */
@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Literal -> value
    // ⚠️ Two explicit calls rather than one spread. See [UiText.Resource.arg].
    is UiText.Resource -> if (arg == null) stringResource(id) else stringResource(id, arg)
}
