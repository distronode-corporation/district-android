package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRoleTest {

    @Test
    fun `parses the three roles the server defines`() {
        assertEquals(WorkspaceRole.AGENCY, WorkspaceRole.fromWire("agency"))
        assertEquals(WorkspaceRole.CLIENT, WorkspaceRole.fromWire("client"))
        assertEquals(WorkspaceRole.VIEWER, WorkspaceRole.fromWire("viewer"))
    }

    @Test
    fun `is case and whitespace tolerant`() {
        // The server lowercases on both write and read, but the column is free text and older
        // rows predate that.
        assertEquals(WorkspaceRole.AGENCY, WorkspaceRole.fromWire("AGENCY"))
        assertEquals(WorkspaceRole.VIEWER, WorkspaceRole.fromWire(" Viewer "))
    }

    @Test
    fun `fails closed on an unknown role`() {
        // ⛔ NULL, NOT CLIENT. `role` is a plain String column server-side with no Prisma enum
        // and no TypeScript union, so a fourth value is a schema-level possibility. Defaulting
        // to CLIENT would hand mutation controls to whoever arrived with a role string this
        // client does not know — and every one of those actions would come back 403.
        assertNull(WorkspaceRole.fromWire("superuser"))
        assertNull(WorkspaceRole.fromWire(""))
        assertNull(WorkspaceRole.fromWire(null))
    }

    @Test
    fun `excludes viewers from mutations, matching the server's allow-list`() {
        assertTrue(WorkspaceRole.AGENCY.canMutate)
        assertTrue(WorkspaceRole.CLIENT.canMutate)
        assertFalse(WorkspaceRole.VIEWER.canMutate)
    }

    @Test
    fun `an unparsed role allows nothing`() {
        // The extension is defined on the NULLABLE type precisely so this reads naturally and
        // cannot be written as a permissive `!= false`, which is true for null.
        val unknown: WorkspaceRole? = WorkspaceRole.fromWire("nonsense")

        assertFalse(unknown.allowsMutation())
        assertTrue(WorkspaceRole.CLIENT.allowsMutation())
        assertFalse(WorkspaceRole.VIEWER.allowsMutation())
    }
}
