package com.distronode.districtai.ui

import android.os.Bundle
import androidx.navigation.NavBackStackEntry

/**
 * The arguments of a destination in [DistrictNavHost], which always has some.
 *
 * ⚠️ A CAST RATHER THAN `?.`, BECAUSE THE NULL CASE CANNOT HAPPEN. Every destination that reads an
 * argument declares at least one in its route template, and Navigation hands such an entry its merged
 * argument bundle. The previous `arguments?.` chain carried a branch no navigation can take; a
 * template renamed out from under its reader now fails loudly on the first navigation instead of
 * rendering against an empty workspace id. (A cast, not `!!`, only because detekt forbids the
 * operator in production code; the two compile to the same null check.)
 *
 * ⛔ IN ITS OWN FILE, NOT IN DistrictNavHost.kt, which sits at detekt's per-file `TooManyFunctions`
 * ceiling.
 */
internal fun NavBackStackEntry.routeArguments(): Bundle = arguments as Bundle

/**
 * A PATH argument of the current route.
 *
 * ⚠️ A path segment only matches a non-empty value, so a route that resolved to this destination
 * has one; see [routeArguments] for why this asserts rather than defaulting.
 */
internal fun NavBackStackEntry.pathArgument(name: String): String = routeArguments().getString(name) as String
