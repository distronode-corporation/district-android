package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.model.ReplyOption
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.toReplyChoices
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The reply choices after the first, as one navigation argument (the first travels as
 * `replyTo`/`replyChannel`, which push and the compose form also set).
 *
 * ⛔ JSON, NOT A HAND-MADE SEPARATOR. An address can be an email, `+1...`, `fb:123` or `ig:456`, so
 * any character picked as a separator is one a future address may contain. The route builder
 * percent-encodes the result as a whole, and navigation hands it back decoded once.
 *
 * @return null for no choices, so the argument is left off the route entirely.
 */
internal fun encodeReplyChoices(choices: List<ReplyTarget>): String? =
    choices.takeIf { it.isNotEmpty() }?.let { list ->
        ARG_JSON.encodeToString(OPTIONS, list.map { ReplyOption(channel = it.channel, to = it.to) })
    }

/**
 * The choices [encodeReplyChoices] wrote, filtered by the same rule as the list row's.
 *
 * ⚠️ ANYTHING UNREADABLE IS NO CHOICES, NOT A CRASH: a route restored from an older build, or typed
 * by hand into a deep link, leaves the thread with the one choice its `replyTo` names.
 */
internal fun decodeReplyChoices(raw: String?): List<ReplyTarget> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        ARG_JSON.decodeFromString(OPTIONS, raw).toReplyChoices()
    } catch (_: SerializationException) {
        emptyList()
    }
}

private val OPTIONS = ListSerializer(ReplyOption.serializer())

private val ARG_JSON = Json { ignoreUnknownKeys = true }
