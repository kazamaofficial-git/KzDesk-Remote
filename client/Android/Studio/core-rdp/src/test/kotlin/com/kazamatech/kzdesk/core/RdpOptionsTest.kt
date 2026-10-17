package com.kazamatech.kzdesk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test untuk kontrak data inti yang gampang rusak tanpa kelihatan:
 * validasi profil, id stabil perangkat, nilai `wire` audio (dipakai sebagai
 * argumen CLI `/audio-mode:N`), dan flag sertifikat.
 */
class RdpOptionsTest {

    @Test
    fun profilTolakHostKosongAtauSpasi() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfile(host = "   ")
        }
    }

    @Test
    fun profilTolakPortDiLuarRentang() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfile(host = "10.0.0.5", port = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfile(host = "10.0.0.5", port = 70000)
        }
    }

    @Test
    fun idProfilStabilHostPort() {
        assertEquals("10.0.0.5:3389", ConnectionProfile(host = "10.0.0.5").id)
        assertEquals("10.0.0.5:3390", ConnectionProfile(host = "10.0.0.5", port = 3390).id)
    }

    @Test
    fun audioModeWireSesuaiArgumenCliFreeRdp() {
        assertEquals(0, KzAudioMode.DEVICE.wire)
        assertEquals(1, KzAudioMode.REMOTE.wire)
        assertEquals(2, KzAudioMode.OFF.wire)
    }

    @Test
    fun gatewayIdGabunganHostPort() {
        assertEquals("gw.corp.id:443", KzGateway(host = "gw.corp.id").id)
        assertEquals("gw.corp.id:8443", KzGateway(host = "gw.corp.id", port = 8443).id)
    }

    @Test
    fun opsiDefaultAmanUntukUserBaru() {
        val o = RdpOptions()
        assertEquals(KzAudioMode.DEVICE, o.audioMode)
        assertTrue(o.clipboard)
        assertTrue(o.udpTransport)
        assertTrue(o.networkAutoDetect)
        assertTrue(!o.lowBandwidth)
        assertTrue(o.h264)
        assertTrue(o.fontSmoothing)
        assertTrue("resolusi dinamis (kanal DISP) nyala untuk user baru", o.dynamicResolution)
        assertEquals(false, o.microphone)
        assertEquals(false, o.localDrive)
        assertEquals(false, o.camera)
        assertNull(o.gateway)
    }

    @Test
    fun certificateFlagsDibacaDariBitmask() {
        val info = CertificateInfo(
            host = "10.0.0.5",
            port = 3389,
            commonName = "rdp.local",
            subject = "CN=rdp.local",
            issuer = "CN=CA",
            fingerprint = "AA:BB",
            flags = CertificateInfo.FLAG_CHANGED or CertificateInfo.FLAG_GATEWAY,
        )
        assertTrue(info.isChanged)
        assertTrue(info.isGateway)
        assertTrue(!info.isMismatch)
        assertTrue(!info.isRedirect)
    }

    @Test
    fun verifyKonstantaSesuaiKontrakInti() {
        // 1 = diterima, 0 = ditolak (android_verify_certificate_ex).
        assertEquals(1, CertificateInfo.VERIFY_ACCEPT)
        assertEquals(0, CertificateInfo.VERIFY_DENY)
    }

    @Test
    fun resolveForLinkAktifkanLowBandwidthSaatSelulerAtauMetered() {
        val base = RdpOptions(networkAutoDetect = true, lowBandwidth = false)
        assertTrue(base.resolveForLink(isMetered = false, isCellular = true).lowBandwidth)
        assertTrue(base.resolveForLink(isMetered = true, isCellular = false).lowBandwidth)
        assertTrue(base.resolveForLink(isMetered = false, isCellular = false, downstreamKbps = 2500).lowBandwidth)
        assertTrue(!base.resolveForLink(isMetered = false, isCellular = false, downstreamKbps = 50_000).lowBandwidth)

        val manualOff = RdpOptions(networkAutoDetect = false, lowBandwidth = false)
        assertTrue(!manualOff.resolveForLink(isMetered = true, isCellular = true).lowBandwidth)
    }

    @Test
    fun streamProfilePresetsMengaturOpsiPerformaDanCodecDenganBenar() {
        // AUTO adalah preset bawaan, jadi pipeline async harus ikut nyala:
        // tanpa itu sesi default menunggu antrean jaringan sebelum merender.
        val auto = RdpOptions().withStreamProfile(KzStreamProfile.AUTO)
        assertEquals(KzStreamProfile.AUTO, auto.streamProfile)
        assertTrue(auto.asyncUpdate)
        assertTrue(auto.asyncChannels)
        assertTrue(auto.networkAutoDetect)
        assertEquals(32, auto.colorDepth)

        val ultra = RdpOptions().withStreamProfile(KzStreamProfile.ULTRA_LOW_LATENCY)
        assertEquals(KzStreamProfile.ULTRA_LOW_LATENCY, ultra.streamProfile)
        assertTrue(ultra.lowBandwidth)
        assertTrue(ultra.asyncUpdate)
        assertTrue(ultra.asyncChannels)
        assertTrue(ultra.fontSmoothing)
        assertTrue(!ultra.visualThemes)
        assertEquals(24, ultra.colorDepth)
        assertEquals("AVC420", ultra.activeCodecLabel())

        val highVis = ultra.withStreamProfile(KzStreamProfile.HIGH_VISUAL)
        assertEquals(KzStreamProfile.HIGH_VISUAL, highVis.streamProfile)
        assertTrue(!highVis.lowBandwidth)
        assertTrue(highVis.desktopWallpaper)
        assertTrue(highVis.desktopComposition)
        assertTrue(highVis.visualThemes)
        assertEquals(32, highVis.colorDepth)
        assertEquals("AVC444", highVis.activeCodecLabel())

        val saver = highVis.withStreamProfile(KzStreamProfile.DATA_SAVER)
        assertEquals(16, saver.colorDepth)
        assertEquals(2, saver.compressionLevel)
        assertEquals(30, saver.targetFps)
        assertEquals("AVC420", saver.activeCodecLabel())
    }

    @Test
    fun wakeOnLanNormalizeDanMagicPacketValid() {
        assertEquals("AA:BB:CC:11:22:33", WakeOnLan.normalizeMac("aa-bb-cc-11-22-33"))
        assertEquals("AA:BB:CC:11:22:33", WakeOnLan.normalizeMac("aabb.cc11.2233"))
        assertNull(WakeOnLan.normalizeMac("invalid-mac"))
        assertNull(WakeOnLan.normalizeMac("AA:BB:CC:DD:EE"))

        val pkt = WakeOnLan.buildMagicPacket("AA:BB:CC:11:22:33")
        assertEquals(102, pkt.size)
        for (i in 0 until 6) {
            assertEquals(0xFF.toByte(), pkt[i])
        }
        for (rep in 0 until 16) {
            val base = 6 + rep * 6
            assertEquals(0xAA.toByte(), pkt[base])
            assertEquals(0xBB.toByte(), pkt[base + 1])
            assertEquals(0xCC.toByte(), pkt[base + 2])
            assertEquals(0x11.toByte(), pkt[base + 3])
            assertEquals(0x22.toByte(), pkt[base + 4])
            assertEquals(0x33.toByte(), pkt[base + 5])
        }
    }

    @Test
    fun sshTunnelCommandGeneratorMenghasilkanFormatPortForwardingYangTepat() {
        val opts = RdpOptions(
            sshHost = "bastion.kazamadev.id",
            sshPort = 2222,
            sshUser = "devops",
            sshLocalPort = 13389,
        )
        assertEquals(
            "ssh -N -L 13389:10.10.0.25:3389 devops@bastion.kazamadev.id -p 2222",
            WakeOnLan.buildSshTunnelCommand("10.10.0.25", 3389, opts),
        )
        assertNull(WakeOnLan.buildSshTunnelCommand("10.10.0.25", 3389, RdpOptions()))
    }

    @Test
    fun pcStreamEngineMengunciModeKonsolDanMematikanDynamicResolution() {
        val game = RdpOptions().withPcStreamEngine(KzPcStreamEngine.DIRECT_GAME_ULTRA)
        assertTrue(game.pcConnectMode)
        assertTrue(game.consoleAdmin)
        assertTrue(!game.dynamicResolution)
        assertEquals(KzPcStreamEngine.DIRECT_GAME_ULTRA, game.pcStreamEngine)
        assertEquals(32, game.colorDepth)
        assertTrue(game.fontSmoothing)
        assertTrue(game.desktopComposition)
        assertTrue(!game.lowBandwidth)

        val studio = game.withPcStreamEngine(KzPcStreamEngine.DIRECT_STUDIO_444)
        assertEquals(KzPcStreamEngine.DIRECT_STUDIO_444, studio.pcStreamEngine)
        assertEquals(32, studio.colorDepth)
        assertTrue(!studio.lowBandwidth)
    }

    @Test
    fun pcStreamEnginePreservesHandsetRefreshPreference() {
        for (engine in KzPcStreamEngine.entries) {
            assertEquals(90, RdpOptions(pcTargetFps = 90).withPcStreamEngine(engine).pcTargetFps)
            assertEquals(30, RdpOptions(pcTargetFps = 30).withPcStreamEngine(engine).pcTargetFps)
        }
    }

    @Test
    fun lanScannerEncodeIpv4ToPcIdMenghasilkanSepuluhDigitTerformat() {
        assertEquals("323-223-5826", LanScanner.encodeIpv4ToPcId("192.168.1.50"))
        assertNull(LanScanner.encodeIpv4ToPcId("invalid"))
    }

    @Test
    fun smartResolutionHanyaSatuBandingSatuSaatResolusiSamaDenganLayar() {
        // Desktop 1:1 dengan layar -> ClearType tetap dipakai.
        assertTrue(SmartResolution.isPixelPerfect(1080, 2400, 1080, 606))
        // 1080p pada layar 1080 lebar -> diperkecil 0,56x, bukan 1:1.
        assertFalse(SmartResolution.isPixelPerfect(1080, 2400, 1920, 1080))
        assertFalse(SmartResolution.isPixelPerfect(2400, 1080, 1280, 720))
    }

    @Test
    fun smartResolutionForViewportDefaultsToStandard720p() {
        // Automatic stays at 720p in portrait, landscape, and large-screen layouts.
        assertEquals("1280x720", SmartResolution.forViewport(1080, 2400))
        assertEquals("1280x720", SmartResolution.forViewport(2400, 1080))
        assertEquals("1280x720", SmartResolution.forViewport(1440, 3200))
        assertEquals("1280x720", SmartResolution.forViewport(720, 1600))
        assertEquals("1280x720", SmartResolution.forViewport(2560, 1600))
    }
}
