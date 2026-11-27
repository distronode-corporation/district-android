// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import com.distronode.districtai.core.data.HqVoiceRepository
import com.distronode.districtai.core.media.HqVoiceEngineFactory
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * The voice session's rules, against a scripted engine and API: the consent before the
 * microphone, the discard-then-end of a session that never went live, the end of one that did,
 * push-to-talk, and every way a session stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class HqVoiceSessionCase {

    protected val api = FakeHqVoiceApi()
    protected val hq = FakeDistrictApi()
    protected val engines = mutableListOf<FakeHqVoiceEngine>()
    protected var busy = false
    protected var permission = true
    protected val audio = object : HqVoiceAudio {
        override fun isBusy() = busy
        override fun hasMicrophonePermission() = permission
    }

    /** Set before an engine is built, so a test can script its join. */
    protected var prepare: (FakeHqVoiceEngine) -> Unit = {}

    protected fun TestScope.session(canConfirm: Boolean = true) = HqVoiceSession(
        workspaceId = "ws-1",
        canConfirm = canConfirm,
        repository = HqVoiceRepository(api, hq),
        engineFactory = HqVoiceEngineFactory { _, key -> FakeHqVoiceEngine(key).also(prepare).also { engines += it } },
        audio = audio,
        scope = backgroundScope,
    )

    protected val engine get() = engines.single()
    protected val HqVoiceSession.phase get() = state.value.phase

    protected fun TestScope.toNotice(session: HqVoiceSession, history: List<HqTurn> = emptyList()) {
        session.start(history, "en")
        runCurrent()
    }

    protected fun TestScope.toLive(session: HqVoiceSession) {
        toNotice(session)
        session.begin()
        runCurrent()
    }

    protected fun push() {
        api.tokenResult = ApiResult.Success(FakeHqVoiceApi.CREDENTIAL.copy(micMode = "push"))
    }
}
