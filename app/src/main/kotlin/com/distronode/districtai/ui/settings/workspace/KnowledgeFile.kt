package com.distronode.districtai.ui.settings.workspace

import androidx.annotation.StringRes
import com.distronode.districtai.R
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * "Read a text file" on the knowledge screen: a plain text file becomes a DRAFT document.
 *
 * ⛔ NOTHING IS SENT HERE. The file fills the title and text boxes and the operator presses Add,
 * exactly as with pasted text. The add buys an embedding run billed by the text's length, so a
 * pick that uploaded on its own would spend money the operator never looked at.
 *
 * ⚠️ THE SAME RULES AS DISTRICT AI FOR WINDOWS (`district-ffi/src/settings/knowledge.rs`,
 * `knowledge_file`), so a file refused on one platform is refused on the other: at most
 * [MAX_FILE_BYTES]; the first bytes, never the name or the provider's MIME type, decide whether it
 * is text; UTF-8 with or without its mark, or UTF-16 with its mark (Notepad's "Unicode"), and
 * nothing else; no control character but tab, line feed, carriage return and form feed; CRLF
 * becomes LF; and a file with no words in it is refused rather than filling the form with blanks.
 */

/** The MIME types the system file chooser offers. The bytes still decide; this only filters. */
val KNOWLEDGE_FILE_MIME_TYPES: Array<String> = arrayOf("text/plain", "text/markdown", "text/x-markdown")

/**
 * The largest file read in, in bytes (1 MB, as on Windows).
 *
 * ⚠️ A CLIENT RULE, NOT THE SERVER'S. The create route takes content of any length and bills by it
 * (it rate-limits the call, not the size), so this keeps a file to the size a person would paste.
 */
const val MAX_FILE_BYTES: Int = 1024 * 1024

/** The longest title the service keeps (the route stores `title.slice(0, 200)`). */
private const val TITLE_CHARS = 200

/** How many leading bytes the binary sniff looks at, as on Windows. */
private const val SNIFF_BYTES = 12

/**
 * One picked file, as [KnowledgeFileReader] read it.
 *
 * ⚠️ [bytes] holds at most [MAX_FILE_BYTES] + 1: the read stops one byte past the limit, so a larger
 * file is SEEN to be larger without the whole of it being held in memory.
 *
 * ⚠️ [ByteArray] IN A DATA CLASS, SO `equals` IS IDENTITY, the same deliberate choice as
 * `PickedAttachment`: nothing compares two of these.
 */
@Suppress("ArrayInDataClass")
data class PickedTextFile(val fileName: String, val bytes: ByteArray)

/** Why a picked file was not put in the form, each with the words the screen shows. */
enum class KnowledgeFileRefusal(@StringRes val message: Int) {
    TOO_LARGE(R.string.knowledge_file_too_large),
    EMPTY(R.string.knowledge_file_empty),
    NOT_TEXT(R.string.knowledge_file_not_text),
    NO_TEXT(R.string.knowledge_file_no_text),

    /** The provider gave nothing to read: a revoked grant, a deleted file, a failed download. */
    UNREADABLE(R.string.knowledge_file_unreadable),
}

/** What a picked file gave: a title and text for the form, or why it was refused. */
sealed interface KnowledgeFileRead {
    /** [title] is the file's name less its extension, offered only for an empty title box. */
    data class Text(val title: String, val content: String) : KnowledgeFileRead

    data class Refused(val reason: KnowledgeFileRefusal) : KnowledgeFileRead
}

/** [file] as a document's title and text, or why it is refused. */
fun readKnowledgeFile(file: PickedTextFile): KnowledgeFileRead {
    val bytes = file.bytes
    val text = when {
        bytes.size > MAX_FILE_BYTES -> return KnowledgeFileRead.Refused(KnowledgeFileRefusal.TOO_LARGE)
        bytes.isEmpty() -> return KnowledgeFileRead.Refused(KnowledgeFileRefusal.EMPTY)
        isKnownBinary(bytes) -> null
        else -> decode(bytes)
    }
    val clean = text?.takeIf { decoded -> decoded.none { it.isISOControl() && it !in ALLOWED_CONTROLS } }
        ?: return KnowledgeFileRead.Refused(KnowledgeFileRefusal.NOT_TEXT)
    val content = clean.replace("\r\n", "\n")
    if (content.isBlank()) return KnowledgeFileRead.Refused(KnowledgeFileRefusal.NO_TEXT)
    return KnowledgeFileRead.Text(title = titleOf(file.fileName), content = content)
}

private val ALLOWED_CONTROLS = setOf('\t', '\n', '\r', '\u000C')

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

/**
 * [bytes] as text: UTF-8 with or without its mark, or UTF-16 with its mark. Anything else, a
 * Latin-1 file included, is null (not plain text) rather than a guess that shows mojibake.
 */
private fun decode(bytes: ByteArray): String? = when {
    bytes.startsWith(UTF8_BOM) -> strict(Charsets.UTF_8, bytes, UTF8_BOM.size)
    bytes.startsWith(UTF16LE_BOM) -> strict(Charsets.UTF_16LE, bytes, UTF16LE_BOM.size)
    bytes.startsWith(UTF16BE_BOM) -> strict(Charsets.UTF_16BE, bytes, UTF16BE_BOM.size)
    else -> strict(Charsets.UTF_8, bytes, 0)
}

/**
 * ⛔ REPORT, NEVER REPLACE. `String(bytes, charset)` swaps a malformed sequence for U+FFFD and
 * succeeds, which would put replacement characters into a billed document instead of refusing it.
 * A truncated UTF-16 unit or a lone surrogate is malformed too.
 */
private fun strict(charset: Charset, bytes: ByteArray, skip: Int): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/**
 * The binary kinds Windows names by their first bytes (`files.rs` `file_kind`): PNG, JPEG, GIF,
 * PDF, WebP, and HEIC or HEIF. A `.txt` holding any of them is not text, whatever its name says.
 */
private fun isKnownBinary(bytes: ByteArray): Boolean {
    // ⚠️ ISO-8859-1 maps each byte to the char of the same value, so byte prefixes compare as text.
    val head = String(bytes.copyOf(minOf(bytes.size, SNIFF_BYTES)), Charsets.ISO_8859_1)
    val isoBox = head.length == SNIFF_BYTES && (
        head.startsWith("RIFF") && head.endsWith("WEBP") ||
            head.startsWith("ftyp", startIndex = BOX_TYPE_AT) && head.substring(BRAND_AT) in ISO_BRANDS
        )
    return isoBox || BINARY_PREFIXES.any { head.startsWith(it) }
}

/** Where an ISO media file's first box names its type (`ftyp`) and then its major brand. */
private const val BOX_TYPE_AT = 4
private const val BRAND_AT = 8

/** PNG, JPEG, GIF (both versions) and PDF, as ISO-8859-1 text. */
private val BINARY_PREFIXES: List<String> =
    listOf("\u0089PNG\r\n\u001A\n", "\u00FF\u00D8\u00FF", "GIF87a", "GIF89a", "%PDF-")

/** HEIC's and HEIF's major brands, as Windows lists them. */
private val ISO_BRANDS =
    setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/**
 * The title a file's name suggests: without any folder, its last extension and any control
 * character, no longer than the service keeps; the name as it is when dropping the extension leaves
 * nothing (".txt" stays ".txt").
 */
internal fun titleOf(fileName: String): String {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }
    val stem = name.substringBeforeLast('.', missingDelimiterValue = name).ifBlank { name }.trim()
    // ⚠️ By code point, so a title of emoji is never cut through a surrogate pair.
    val points = stem.codePointCount(0, stem.length)
    val end = if (points > TITLE_CHARS) stem.offsetByCodePoints(0, TITLE_CHARS) else stem.length
    return stem.substring(0, end)
}
