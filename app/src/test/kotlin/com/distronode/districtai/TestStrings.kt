package com.distronode.districtai

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider

/**
 * A string from the app's resources, as the test's own configuration resolves it.
 *
 * ⚠️ WHY TESTS LOOK UP COPY BY RESOURCE. The English lives in one catalog (`l10n/android.toml`), and
 * a lookup that spells the English out pins the test to one locale: the same test run under
 * `fr-rCA` would fail on a screen that is correct. Reading the resource keeps the assertion about
 * WHICH string is on screen, in whatever language the run is in. Fixture and server text stays a
 * literal, because it is data, not copy.
 */
fun str(@StringRes id: Int, vararg args: Any): String =
    ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

/** A plural from the app's resources; [count] selects the form and is the first format argument. */
fun plural(@PluralsRes id: Int, count: Int): String =
    ApplicationProvider.getApplicationContext<Context>().resources.getQuantityString(id, count, count)

/** A plural with format arguments of its own: [count] selects the form, [args] fill it. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    ApplicationProvider.getApplicationContext<Context>().resources.getQuantityString(id, count, *args)
