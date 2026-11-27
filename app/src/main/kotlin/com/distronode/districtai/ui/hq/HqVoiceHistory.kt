// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq

import com.distronode.districtai.core.model.HqTurn

/**
 * The console's turns as a voice token request carries them, so the agent knows the conversation
 * so far. The request bounds them (the last six, each cut to 500 characters).
 *
 * ⚠️ THE APP'S OWN LINES ARE LEFT OUT ("Applied."), as the console's own history leaves them out:
 * neither party said them.
 */
internal fun List<HqMessage>.voiceHistory(): List<HqTurn> = mapNotNull { message ->
    message.text.literalOrNull?.let { HqTurn(role = message.role.wireRole, text = it) }
}
