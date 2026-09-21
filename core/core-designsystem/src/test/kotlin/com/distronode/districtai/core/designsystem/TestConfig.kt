package com.distronode.districtai.core.designsystem

/**
 * The Robolectric API level every test in this module runs at.
 *
 * ⚠️ TRACKS ROBOLECTRIC'S NEWEST SUPPORTED LEVEL, NOT THE APP'S `targetSdk`. Robolectric 4.16.1
 * ships no API 36/37 runtime jars, so pointing `@Config` at the real target fails to start the
 * environment at all — with a message about a missing jar rather than about a version mismatch.
 *
 * ⚠️ DUPLICATED FROM THE APP MODULE'S `TestSupport.kt`, DELIBERATELY. This module depends on nothing
 * in the project (see its build file), and taking a test dependency on `:app` to share one integer
 * would invert the dependency direction — the design system would then need the app to build. A
 * Robolectric upgrade has to change both; there are two, and this comment is how the second is found.
 */
internal const val ROBOLECTRIC_SDK = 35
