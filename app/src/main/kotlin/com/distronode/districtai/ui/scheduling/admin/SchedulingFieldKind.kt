package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.ui.text.input.KeyboardType

/** How a [SchedulingTextField] takes its input. */
enum class SchedulingFieldKind(val keyboard: KeyboardType, val singleLine: Boolean, val secret: Boolean) {
    TEXT(KeyboardType.Text, singleLine = true, secret = false),
    MULTILINE(KeyboardType.Text, singleLine = false, secret = false),
    NUMBER(KeyboardType.Number, singleLine = true, secret = false),
    EMAIL(KeyboardType.Email, singleLine = true, secret = false),
    URL(KeyboardType.Uri, singleLine = true, secret = false),

    /** ⛔ A credential: masked, and never offered to the keyboard's suggestions. */
    PASSWORD(KeyboardType.Password, singleLine = true, secret = true),
}
