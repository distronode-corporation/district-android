package com.distronode.districtai.ui.settings.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a picked file becomes a draft document, case for case with District AI for Windows
 * (`district-ffi/src/settings/knowledge.rs`, its `knowledge_file` tests), so the two platforms
 * accept and refuse the same files.
 */
class KnowledgeFileTest {

    private fun file(name: String, bytes: ByteArray) = PickedTextFile(name, bytes)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun refused(reason: KnowledgeFileRefusal) = KnowledgeFileRead.Refused(reason)

    @Test
    fun `a text file fills the form, its CRLF made LF`() {
        assertEquals(
            KnowledgeFileRead.Text(title = "Refund policy", content = "We refund in 30 days.\n"),
            readKnowledgeFile(file("Refund policy.txt", "We refund in 30 days.\r\n".toByteArray())),
        )
    }

    @Test
    fun `UTF-8 with its mark and UTF-16 in both orders read the same`() {
        val text = "Open 9 to 5, café"
        val marked = bytes(0xEF, 0xBB, 0xBF) + text.toByteArray(Charsets.UTF_8)
        val le = bytes(0xFF, 0xFE) + text.toByteArray(Charsets.UTF_16LE)
        val be = bytes(0xFE, 0xFF) + text.toByteArray(Charsets.UTF_16BE)

        listOf(marked, le, be).forEach {
            assertEquals(KnowledgeFileRead.Text("hours", text), readKnowledgeFile(file("hours.md", it)))
        }
    }

    @Test
    fun `tab, form feed and line breaks are text`() {
        val content = "a\tb\u000Cc\rd\ne"
        assertEquals(KnowledgeFileRead.Text("t", content), readKnowledgeFile(file("t.txt", content.toByteArray())))
    }

    @Test
    fun `a file that is not text is refused by its bytes, whatever its name`() {
        val cases = mapOf(
            "scan.txt" to "%PDF-1.7\n%".toByteArray() + bytes(0xE2, 0xE3, 0xCF, 0xD3),
            "photo.md" to bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D),
            "jpeg.txt" to bytes(0xFF, 0xD8, 0xFF, 0xE0),
            "anim.txt" to "GIF89a....".toByteArray(),
            "old.txt" to "GIF87a....".toByteArray(),
            "web.txt" to "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(),
            "heic.txt" to "\u0000\u0000\u0000\u0018ftypheic".toByteArray(),
            "heif.txt" to "\u0000\u0000\u0000\u0018ftypmif1".toByteArray(),
            "latin1.txt" to bytes(0x63, 0x61, 0x66, 0xE9),
            "binary.txt" to "abc\u0000def".toByteArray(),
            "delete.txt" to "abc\u007Fdef".toByteArray(),
            "c1.txt" to "abc\u0085def".toByteArray(),
            "odd.txt" to bytes(0xFF, 0xFE, 0x41),
            "lone.txt" to bytes(0xFF, 0xFE, 0x00, 0xD8),
            "bad.txt" to bytes(0xEF, 0xBB, 0xBF, 0xFF),
            "badbe.txt" to bytes(0xFE, 0xFF, 0xDC, 0x00),
        )
        cases.forEach { (name, content) ->
            assertEquals(name, refused(KnowledgeFileRefusal.NOT_TEXT), readKnowledgeFile(file(name, content)))
        }
    }

    @Test
    fun `text that only looks like a container's start is still text`() {
        // Twelve bytes are needed to name RIFF or ftyp, and an unknown brand is no kind at all.
        listOf("RIFF", "RIFF and more", "1234ftypabcd", "ftyp").forEach {
            assertTrue(it, readKnowledgeFile(file("note.txt", it.toByteArray())) is KnowledgeFileRead.Text)
        }
    }

    @Test
    fun `an empty, a blank and a too-large file are each refused with their own reason`() {
        assertEquals(refused(KnowledgeFileRefusal.EMPTY), readKnowledgeFile(file("empty.txt", ByteArray(0))))
        assertEquals(
            refused(KnowledgeFileRefusal.NO_TEXT),
            readKnowledgeFile(file("blank.txt", " \r\n\t ".toByteArray())),
        )
        val large = ByteArray(MAX_FILE_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(refused(KnowledgeFileRefusal.TOO_LARGE), readKnowledgeFile(file("large.txt", large)))
        assertTrue(readKnowledgeFile(file("large.txt", large.copyOf(MAX_FILE_BYTES))) is KnowledgeFileRead.Text)
    }

    @Test
    fun `the title loses folder, extension and control characters`() {
        assertEquals("notes.v2", titleOf("../a/b/notes.v2.txt"))
        assertEquals("Refund policy", titleOf("C:\\Docs\\Refund policy.txt"))
        assertEquals("roof", titleOf("ro\r\nof.txt"))
        assertEquals(".txt", titleOf(".txt"))
        assertEquals("README", titleOf("README"))
        assertEquals("", titleOf("folder\\"))
        assertEquals("", titleOf(""))
        assertEquals(200, titleOf("a".repeat(300) + ".txt").length)
        assertEquals(200, titleOf("a".repeat(200) + ".txt").length)
    }

    @Test
    fun `a long title is cut by code point, never through a surrogate pair`() {
        val title = titleOf("\uD83D\uDE00".repeat(250) + ".txt")
        assertEquals(200, title.codePointCount(0, title.length))
        assertEquals("\uD83D\uDE00".repeat(200), title)
    }

    @Test
    fun `the chooser offers plain text and markdown only`() {
        assertEquals(listOf("text/plain", "text/markdown", "text/x-markdown"), KNOWLEDGE_FILE_MIME_TYPES.toList())
        assertEquals(1024 * 1024, MAX_FILE_BYTES)
    }
}
