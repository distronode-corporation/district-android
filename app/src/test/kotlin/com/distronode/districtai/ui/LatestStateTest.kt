package com.distronode.districtai.ui

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestStateTest {

    private sealed interface Screen {
        data object Loading : Screen
        data class Content(val a: Boolean = false, val b: Boolean = false) : Screen
    }

    @Test
    fun `the block sees the latest value, not an earlier snapshot`() {
        val flow = MutableStateFlow<Screen>(Screen.Content())
        val snapshot = flow.value as Screen.Content
        flow.value = snapshot.copy(b = true)

        flow.updateLatest(Screen.Content::class.java) { it.copy(a = true) }

        assertEquals(Screen.Content(a = true, b = true), flow.value)
    }

    @Test
    fun `a value of another type is left alone`() {
        val flow = MutableStateFlow<Screen>(Screen.Loading)

        flow.updateLatest(Screen.Content::class.java) { it.copy(a = true) }

        assertEquals(Screen.Loading, flow.value)
    }
}
