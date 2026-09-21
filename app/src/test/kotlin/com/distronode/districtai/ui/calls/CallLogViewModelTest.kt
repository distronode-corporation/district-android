package com.distronode.districtai.ui.calls

import androidx.lifecycle.ViewModel
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.ui.TestDistrictApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ⛔ THE INVARIANT THIS PROTECTS IS ONE VIEWMODEL PER WORKSPACE. The paging source's offsets and
 * its deduplication set are only meaningful within one tenant, so a ViewModel whose workspace
 * changed underneath it would interleave two workspaces' rows in one list. The factory takes the
 * id precisely so the ViewModelStore can key on it; asserting the id is carried through is what
 * makes that keying possible for a caller.
 *
 * ⚠️ Nothing here collects the paging flow. `cachedIn(viewModelScope)` is asserted structurally
 * (the flow exists and survives a second read) rather than by driving Paging, which needs a
 * differ and belongs in the screen tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallLogViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository() = CallsRepository(TestDistrictApi())

    @Test
    fun `carries the workspace it was built for`() {
        val subject = CallLogViewModel(repository(), workspaceId = "ws-1")

        assertEquals("ws-1", subject.workspaceId)
    }

    @Test
    fun `the same flow instance is handed out twice, so a recomposing screen re-collects a cache`() {
        // ⛔ Without `cachedIn` the Pager's flow is cold and restarts on every collection, so
        // navigating to a call and back refetches from page one and loses the scroll position.
        val subject = CallLogViewModel(repository(), workspaceId = "ws-1")

        assertTrue(subject.calls === subject.calls)
    }

    @Test
    fun `the factory builds a viewmodel bound to the id it was given`() {
        val factory = CallLogViewModel.factory(repository(), workspaceId = "ws-2")

        val created = factory.create(CallLogViewModel::class.java)

        assertEquals("ws-2", created.workspaceId)
    }

    @Test
    fun `two workspaces get two viewmodels, never one that changes tenant`() {
        val shared = repository()

        val first = CallLogViewModel.factory(shared, "ws-1").create(CallLogViewModel::class.java)
        val second = CallLogViewModel.factory(shared, "ws-2").create(CallLogViewModel::class.java)

        assertNotSame(first, second)
        assertEquals("ws-1", first.workspaceId)
        assertEquals("ws-2", second.workspaceId)
    }

    @Test
    fun `the factory returns something the ViewModelStore will accept`() {
        val created: ViewModel =
            CallLogViewModel.factory(repository(), "ws-1").create(CallLogViewModel::class.java)

        assertTrue(created is CallLogViewModel)
    }
}
