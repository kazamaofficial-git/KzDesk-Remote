package com.kazamatech.kzdesk.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** True saat aktivitas sesi berada dalam jendela Picture-in-Picture (kontrol HUD disembunyikan). */
object KzPipState {
    var active by mutableStateOf(false)
}
