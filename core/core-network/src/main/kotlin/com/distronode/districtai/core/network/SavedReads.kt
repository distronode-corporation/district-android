package com.distronode.districtai.core.network

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl

/**
 * Works offline, read-only (the iOS app's `OfflineReadCache`, district-core-rust's saved reads):
 * the last answer of the reads the first screens make, shown again when the service cannot be
 * reached.
 *
 * ⛔ FIVE READS AND NEVER A WRITE ([SAVED_PATHS]): the workspace list, the overview, the inbox's
 * conversations, the calls and the contacts. A request always goes to the network first; the
 * saved copy answers only when it could not reach the service ([ApiResult.NetworkFailure], which
 * includes a refresh that could not leave the device). An answer from the service, a 4xx or a 5xx,
 * is never covered over.
 *
 * ⛔ CUSTOMER DATA. A copy older than [MAX_AGE_MILLIS] is never shown, a body over [MAX_BYTES] is
 * never kept, the language is part of the key (a French answer never fills an English screen),
 * and every sign-out clears them ([clear]), as does the service ending the session.
 *
 * @param language the language the request is sent in, as `Accept-Language` names it.
 * @param clock now, in epoch milliseconds.
 */
class SavedReads(
    private val cache: ReadCache,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A saved read's request, as it was before it was sent. */
    inner class Asked internal constructor(
        internal val key: String,
        internal val generation: Int,
        /** When the request was sent: the time its copy is stamped with. */
        internal val askedAtMillis: Long,
    ) {
        /** The service answered it with [text], which decoded: keep it. */
        fun answered(text: String) = this@SavedReads.answered(this, text)
    }

    private val lock = Any()

    /** Moved on by every [clear], so an answer to a request sent before a sign-out is not kept. */
    private var generation = 0

    /** The saved copies the last answers were, by key, with when each was saved. */
    private val shown = mutableMapOf<String, Long>()

    /**
     * When the request whose answer was last kept, by key, was sent. An answer to an older request
     * (two in flight, answering out of order) is not kept over it.
     */
    private val kept = mutableMapOf<String, Long>()

    private val _showingSince = MutableStateFlow<Long?>(null)

    /**
     * When the oldest saved copy on screen was saved, or null when every read answered live. While
     * it is set the app is offline and read-only, and says so with this time.
     */
    val showingSince: StateFlow<Long?> = _showingSince.asStateFlow()

    /** Taken before the request to [url] is sent; null for anything but a saved read. */
    fun asking(segments: List<String>, url: HttpUrl): Asked? {
        if (segments !in SAVED_PATHS) return null
        return synchronized(lock) { Asked("${language()} $url", generation, clock()) }
    }

    private fun answered(asked: Asked, text: String) {
        val keep = synchronized(lock) {
            shown.remove(asked.key)
            publish()
            // (A body that decoded is never empty.)
            val newest = (kept[asked.key] ?: Long.MIN_VALUE) <= asked.askedAtMillis
            (asked.generation == generation && newest && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
                .also { if (it) kept[asked.key] = asked.askedAtMillis }
        }
        if (!keep) return
        // ⚠️ OUTSIDE THE LOCK: writing a file must not hold up every other request's bookkeeping.
        // Stamped with when the request was sent, so an answer never claims to be fresher than it is.
        cache.save(asked.key, SavedRead(text, asked.askedAtMillis))
        // A clear that landed while it was being written still wins: it belonged to the session
        // that ended. Only this copy goes: one a newer session has saved since is its own.
        if (synchronized(lock) { asked.generation != generation }) cache.remove(asked.key)
    }

    /**
     * What the caller is told, given what [asked] got back: for an unreachable service, the saved
     * copy [decode] reads, if one is young enough; for a session the service ended, the failure,
     * after forgetting every copy.
     */
    fun <T> answer(asked: Asked?, result: ApiResult<T>, decode: (String) -> T?): ApiResult<T> {
        if (result is ApiResult.Unauthorized) clear()
        if (asked == null) return result
        if (result !is ApiResult.NetworkFailure) {
            // ⚠️ The service answered, even if with a refusal or a 5xx: this read is no longer a
            // saved copy on screen, so it must not keep the banner up once the connection is back.
            synchronized(lock) {
                shown.remove(asked.key)
                publish()
            }
            return result
        }
        // Read outside the lock, as a save is written. ⚠️ A copy dated in the future (the clock
        // was moved back) is not trusted either.
        val read = cache.load(asked.key)?.takeIf { clock() - it.savedAtMillis in 0..MAX_AGE_MILLIS }
        val decoded = read?.let { saved -> decode(saved.body) }
        val showing = synchronized(lock) {
            // ⚠️ Not a copy a clear has since forgotten. And a copy that cannot be shown (expired,
            // evicted, unreadable) is off the screen too: the read is now the offline error.
            val shows = if (read != null && decoded != null && asked.generation == generation) {
                shown[asked.key] = read.savedAtMillis
                true
            } else {
                shown.remove(asked.key)
                false
            }
            publish()
            shows
        }
        return decoded?.takeIf { showing }?.let { ApiResult.Success(it) } ?: result
    }

    /** Forgets every saved read. Every sign-out calls it; a read in flight keeps nothing. */
    fun clear() {
        synchronized(lock) {
            generation += 1
            shown.clear()
            kept.clear()
            publish()
        }
        // Outside the lock, like every other use of the cache; see [answered].
        cache.clear()
    }

    private fun publish() {
        _showingSince.value = shown.values.minOrNull()
    }

    companion object {
        /** Seven days: a copy older than this is not shown. */
        const val MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        /** Two megabytes: a body larger than this is not kept. */
        const val MAX_BYTES: Int = 2 * 1024 * 1024

        /** The five reads whose last answer is kept, by path. */
        internal val SAVED_PATHS: Set<List<String>> = setOf(
            DistrictPaths.WORKSPACE_LIST,
            DistrictPaths.OVERVIEW,
            DistrictPaths.CONVERSATIONS,
            DistrictPaths.CALLS,
            DistrictPaths.CONTACTS,
        )
    }
}

/** One read's last answer: the body as the service sent it, and when. */
data class SavedRead(val body: String, val savedAtMillis: Long)

/** Where [SavedReads] keeps them. An interface so the rules are tested without files. */
interface ReadCache {
    fun load(key: String): SavedRead?
    fun save(key: String, read: SavedRead)
    fun remove(key: String)
    fun clear()
}

/**
 * The saved reads as files in [dir], which is the app's cache directory (never backed up) and the
 * cache's alone: [clear] removes every file in it.
 *
 * ⛔ A FILE NAME IS THE SHA-256 OF THE KEY, so no workspace id, query or language appears in a
 * path. A file is the time it was saved on one line, then the body. Each save keeps the newest
 * [MAX_FILES]. Best effort throughout: a cache that cannot be written leaves the app online-only,
 * which is what it was without one, so a failure is not reported.
 */
class FileReadCache(private val dir: File) : ReadCache {

    override fun load(key: String): SavedRead? = try {
        val text = File(dir, fileName(key)).readText(Charsets.UTF_8)
        val newline = text.indexOf('\n')
        text.substring(0, newline.coerceAtLeast(0)).toLongOrNull()?.let { SavedRead(text.substring(newline + 1), it) }
    } catch (_: IOException) {
        null
    }

    override fun save(key: String, read: SavedRead) {
        try {
            dir.mkdirs()
            val file = File(dir, fileName(key))
            val temporary = File(dir, ".${file.name}.tmp")
            temporary.writeText("${read.savedAtMillis}\n${read.body}", Charsets.UTF_8)
            if (temporary.renameTo(file)) prune() else temporary.delete()
        } catch (_: IOException) {
            // Best effort; see the class note.
        }
    }

    override fun remove(key: String) {
        File(dir, fileName(key)).delete()
    }

    /** Every saved read, and every temporary file a save left (customer data too, half-written). */
    override fun clear() {
        (files() + files(TEMPORARY_NAME)).forEach { it.delete() }
    }

    /**
     * Keeps the newest [MAX_FILES], by when each was last written, and removes a temporary file a
     * crash left behind: one older than any write takes is not a write in progress.
     */
    private fun prune() {
        files().sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
        val stale = System.currentTimeMillis() - TEMPORARY_MAX_AGE_MILLIS
        files(TEMPORARY_NAME).filter { it.lastModified() < stale }.forEach { it.delete() }
    }

    /**
     * The saved reads' files in [dir]; none when it does not exist yet. ⚠️ ONLY ITS OWN, named as
     * [fileName] names them: a save writes a temporary `.name.tmp` first, and listing those would
     * let a save or a clear on another thread delete one mid-write.
     */
    private fun files(name: Regex = SAVED_NAME): List<File> =
        dir.walk().maxDepth(1).filter { it.isFile && name.matches(it.name) }.toList()

    companion object {
        /** At most this many saved reads are kept. */
        const val MAX_FILES: Int = 64

        private val SAVED_NAME = Regex("[0-9a-f]{64}")

        /** A save's temporary file: `.`, a saved read's name, `.tmp`. */
        private val TEMPORARY_NAME = Regex("\\.[0-9a-f]{64}\\.tmp")

        /** How old a temporary file must be before a save removes it as left over. */
        private const val TEMPORARY_MAX_AGE_MILLIS: Long = 10L * 60 * 1000

        internal fun fileName(key: String): String = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
