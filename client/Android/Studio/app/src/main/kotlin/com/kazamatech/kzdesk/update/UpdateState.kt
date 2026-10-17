package com.kazamatech.kzdesk.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }

/** State bersama (Compose) untuk UI update. Diubah hanya oleh [UpdateManager]. */
object UpdateState {
    var phase by mutableStateOf(UpdatePhase.IDLE)
    var info by mutableStateOf<UpdateInfo?>(null)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var readyFile: File? = null
    /** Dialog otomatis hanya dibuka sekali per proses. */
    var promptShown = false
}
