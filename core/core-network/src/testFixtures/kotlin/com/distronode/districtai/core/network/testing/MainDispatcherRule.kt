package com.distronode.districtai.core.network.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs [dispatcher] as `Dispatchers.Main` for each test and restores it afterwards.
 *
 * ⛔ `viewModelScope` IS HARD-WIRED TO `Dispatchers.Main`, which does not exist on the JVM, so every
 * ViewModel test needs this, and it was copied into some forty files as an `@Before`/`@After` pair.
 * A copy that forgets the `@After` leaks its dispatcher into whichever test runs next in the same
 * JVM, which reads as an unrelated flake two files away.
 *
 * ⚠️ PASS THE TEST'S OWN DISPATCHER when the test also drives it (`runTest(dispatcher)`,
 * `advanceUntilIdle`); a rule-owned dispatcher and a test-owned one are two clocks, and a launch on
 * one is never advanced by the other. Declare that dispatcher above the rule: properties initialise
 * in declaration order.
 *
 * ⚠️ RUNS OUTSIDE `@Before` AND `@After`. Main is set before any `@Before` and reset after every
 * `@After`, so a tear-down that cancels a scope still does so while Main is the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
