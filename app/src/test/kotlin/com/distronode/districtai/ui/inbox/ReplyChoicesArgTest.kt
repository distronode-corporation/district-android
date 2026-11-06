package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.CHANNEL_INSTAGRAM
import com.distronode.districtai.core.model.CHANNEL_MESSENGER
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.ReplyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reply choices a thread route carries, through [encodeReplyChoices] and back. */
class ReplyChoicesArgTest {

    @Test
    fun `every address form survives the round trip, in order`() {
        val choices = listOf(
            ReplyTarget("ada+billing@example.com", CHANNEL_EMAIL),
            ReplyTarget("+14165550142", CHANNEL_SMS),
            ReplyTarget("+14165550142", CHANNEL_WHATSAPP),
            ReplyTarget("fb:123", CHANNEL_MESSENGER),
            ReplyTarget("ig:456", CHANNEL_INSTAGRAM),
            // Every separator a hand-made format might have picked.
            ReplyTarget("a,b;c&d=e?f#g%h\"i\\j", CHANNEL_EMAIL),
        )

        assertEquals(choices, decodeReplyChoices(encodeReplyChoices(choices)))
    }

    @Test
    fun `no choices leave the argument off`() {
        assertNull(encodeReplyChoices(emptyList()))
        assertEquals(emptyList<ReplyTarget>(), decodeReplyChoices(null))
        assertEquals(emptyList<ReplyTarget>(), decodeReplyChoices(" "))
    }

    @Test
    fun `an unreadable value is no choices, never a crash`() {
        listOf("not json", "{}", "[1,2]", """[{"channel":5}]""").forEach {
            assertEquals(it, emptyList<ReplyTarget>(), decodeReplyChoices(it))
        }
    }

    @Test
    fun `a decoded value is held to the list row's rule`() {
        assertEquals(
            listOf(ReplyTarget("ada@example.com", CHANNEL_EMAIL)),
            decodeReplyChoices(
                """[{"channel":"rcs","to":"+1"},{"channel":"sms","to":""},""" +
                    """{"channel":"email","to":"ada@example.com","extra":true}]""",
            ),
        )
    }
}
