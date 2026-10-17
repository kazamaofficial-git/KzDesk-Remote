package com.kazamatech.kzdesk.core

/** A disabled UDP audio bridge must never open a second microphone capture device. */
fun RdpOptions.usesQuicMicrophone(): Boolean = quicAudio && microphone
