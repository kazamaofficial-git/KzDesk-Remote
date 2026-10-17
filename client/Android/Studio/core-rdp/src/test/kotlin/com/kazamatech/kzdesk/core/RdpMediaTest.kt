package com.kazamatech.kzdesk.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpMediaTest {
    @Test fun microphoneOverBridgeNeedsBothFlags() {
        assertTrue(RdpOptions(quicAudio = true, microphone = true).usesQuicMicrophone())
        assertFalse(RdpOptions(quicAudio = false, microphone = true).usesQuicMicrophone())
        assertFalse(RdpOptions(quicAudio = true, microphone = false).usesQuicMicrophone())
    }
}
