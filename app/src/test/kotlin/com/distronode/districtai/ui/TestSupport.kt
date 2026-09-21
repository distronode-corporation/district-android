package com.distronode.districtai.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/**
 * The Robolectric API level every test in this module runs at.
 *
 * ⚠️ TRACKS ROBOLECTRIC'S NEWEST SUPPORTED LEVEL, NOT THE APP'S `targetSdk`. Robolectric 4.16.1
 * ships no API 36/37 runtime jars, so pointing `@Config` at the real target fails to start the
 * environment at all — with a message about a missing jar rather than about a version mismatch.
 *
 * ⚠️ DECLARED ONCE, HERE. It was previously a `private const` copied into four test files, which
 * is a number that only grows; a per-file copy also means a Robolectric upgrade has to find them
 * all, and the one it misses fails in a way that looks unrelated.
 */
internal const val ROBOLECTRIC_SDK = 35

/**
 * Resolve a [UiText] outside composition, for tests that assert on WORDING.
 *
 * ⛔ THIS MIRRORS THE `@Composable` [resolve], WHICH IS A DRIFT RISK, AND `UiTextTest` IS THE
 * GUARD. The production path must go through `stringResource` — that is what makes a message
 * re-resolve when the locale or font scale changes, and reading it from a captured `Context`
 * instead is the `LocalContextGetResourceValueCall` lint error this branch also fixes. A test that
 * is not itself a composable cannot call it, so there are two implementations; `UiTextTest`
 * asserts they agree for every shape a [UiText] can take.
 *
 * ⚠️ Only for assertions about what the user reads. A test that just needs to know WHICH message
 * was chosen should use [UiText.resourceIdOrNull] / [UiText.literalOrNull] instead and stay off
 * Robolectric entirely.
 */
internal fun UiText.resolveInTest(
    context: Context = ApplicationProvider.getApplicationContext(),
): String = when (this) {
    is UiText.Literal -> value
    is UiText.Resource -> if (arg == null) context.getString(id) else context.getString(id, arg)
}
