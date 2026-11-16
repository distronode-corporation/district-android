package com.distronode.districtai.ui.hq.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Holds one workspace's [HqVoiceSession] across a rotation.
 *
 * ⛔ CLEARED MEANS ENDED. The member left HQ for good (or the workspace changed), so a session
 * still under way is ended; the end runs on the session's application scope, so it reaches the
 * service after this ViewModel is gone.
 */
class HqVoiceViewModel(val session: HqVoiceSession) : ViewModel() {

    override fun onCleared() {
        session.end()
    }

    companion object {
        fun factory(session: () -> HqVoiceSession): ViewModelProvider.Factory = viewModelFactory {
            initializer { HqVoiceViewModel(session()) }
        }
    }
}
