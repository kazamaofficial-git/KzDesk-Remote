package com.kazamatech.kzdesk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test pemetaan opsi -> argumen FreeRDP.
 *
 * Yang diuji di sini adalah bagian MURNI (tanpa Context/Uri): nama dan nilai
 * query param, karena inti FreeRDP menerjemahkan tiap param jadi argumen CLI
 * (`p` -> `/p:`, `multitransport=+` -> `+multitransport`, dst). Salah nama
 * key = fitur diam-diam mati tanpa error di UI.
 */
class RdpUriTest {

    private fun params(
        profile: ConnectionProfile,
        options: RdpOptions = RdpOptions(),
    ): Map<String, String> = RdpUri.queryParams(profile, options).toMap()

    private fun simple(
        host: String = "10.0.0.5",
        port: Int = 3389,
        user: String? = "kal",
        pass: String? = null,
        domain: String? = null,
    ) = ConnectionProfile(host = host, port = port, username = user, password = pass, domain = domain)

    // ---------------------------------------------------------------- authority

    @Test
    fun authorityTulisPortHanyaKalauBukanDefault() {
        assertEquals("kal@10.0.0.5", RdpUri.authority(simple()))
        assertEquals("kal@10.0.0.5:3390", RdpUri.authority(simple(port = 3390)))
        // tanpa user, authority murni host[:port]
        assertEquals("10.0.0.5", RdpUri.authority(simple(user = null)))
    }

    @Test
    fun authorityIpv6MemakaiBracketDanPortYangBenar() {
        assertEquals("[2001:db8::1]", RdpUri.authority(simple(host = "2001:db8::1", user = null)))
        assertEquals("kal@[2001:db8::1]:3390", RdpUri.authority(simple(host = "2001:db8::1", port = 3390)))
    }

    @Test
    fun authoritySertakanUserKalauAda() {
        assertEquals("kal@10.0.0.5", RdpUri.authority(simple()))
        assertEquals("10.0.0.5", RdpUri.authority(simple(user = null)))
        assertEquals("10.0.0.5", RdpUri.authority(simple(user = "   ")))
    }

    // ---------------------------------------------------------------- default

    @Test
    fun defaultClipboardNyalaAudioDiPerangkat() {
        val p = params(simple())
        assertEquals("0", p["audio-mode"])
        assertTrue("sound harus ikut saat audio di perangkat", p.containsKey("sound"))
        assertEquals("+", p["clipboard"])
        assertEquals("+", p["multitransport"])
        assertEquals("auto", p["network"])
        assertEquals("AVC444", p["gfx"])
        assertEquals("+", p["fonts"])
        assertEquals("+", p["aero"])
    }

    @Test
    fun lowBandwidthUsesConstrainedNetworkAndAvc420Hints() {
        val p = params(simple(), RdpOptions(lowBandwidth = true))
        assertEquals("broadband-low", p["network"])
        assertEquals("AVC420", p["gfx"])
        assertEquals("+", p["multitransport"])
    }

    @Test
    fun lowBandwidthCanStillDisableGfx() {
        val p = params(simple(), RdpOptions(lowBandwidth = true, h264 = false))
        assertEquals("broadband-low", p["network"])
        assertFalse(p.containsKey("gfx"))
    }

    @Test
    fun defaultTidakKirimFiturYangMati() {
        val p = params(simple())
        assertFalse(p.containsKey("microphone"))
        assertFalse(p.containsKey("drive"))
        assertFalse(p.containsKey("dvc"))
        assertFalse(p.containsKey("gateway"))
        assertFalse(p.containsKey("p"))
        assertFalse(p.containsKey("domain"))
    }

    // ---------------------------------------------------------------- kredensial

    @Test
    fun passwordDanDomainIkutTerkirim() {
        val p = params(simple(pass = "rahasia", domain = "CORP"))
        assertEquals("rahasia", p["p"])
        assertEquals("CORP", p["domain"])
    }

    // ---------------------------------------------------------------- audio

    @Test
    fun audioRemoteTidakKirimSound() {
        val p = params(simple(), RdpOptions(audioMode = KzAudioMode.REMOTE))
        assertEquals("1", p["audio-mode"])
        assertFalse(p.containsKey("sound"))
    }

    @Test
    fun audioMatiPakaiModeDua() {
        val p = params(simple(), RdpOptions(audioMode = KzAudioMode.OFF))
        assertEquals("2", p["audio-mode"])
        assertFalse(p.containsKey("sound"))
    }

    @Test
    fun mikrofonIkutSaatDinyalakan() {
        assertTrue(params(simple(), RdpOptions(microphone = true)).containsKey("microphone"))
    }

    // ---------------------------------------------------------------- perangkat

    @Test
    fun clipboardMatiJadiMinus() {
        assertEquals("-", params(simple(), RdpOptions(clipboard = false))["clipboard"])
    }

    @Test
    fun driveLokalDanKamera() {
        val p = params(simple(), RdpOptions(localDrive = true, camera = true))
        assertEquals("KzDesk", p["drive"])
        assertEquals("rdpecam", p["dvc"])
    }

    @Test
    fun resolusiDinamisIkutTerkirim() {
        assertTrue(
            "kanal DISP butuh /dynamic-resolution",
            params(simple()).containsKey("dynamic-resolution"),
        )
    }

    @Test
    fun resolusiDinamisBisaDimatikan() {
        val p = params(simple(), RdpOptions(dynamicResolution = false))
        assertFalse(p.containsKey("dynamic-resolution"))
    }

    @Test
    fun transportMatiTidakKirimMultitransport() {
        val p = params(simple(), RdpOptions(udpTransport = false, networkAutoDetect = false, h264 = false))
        assertFalse(p.containsKey("multitransport"))
        assertFalse(p.containsKey("network"))
        assertFalse(p.containsKey("gfx"))
    }

    // ---------------------------------------------------------------- gateway

    @Test
    fun gatewayArgPortDefaultDihilangkan() {
        assertEquals("g:gw.corp.id", RdpUri.gatewayArg(KzGateway(host = "gw.corp.id")))
        assertEquals(
            "g:gw.corp.id:8443",
            RdpUri.gatewayArg(KzGateway(host = "gw.corp.id", port = 8443)),
        )
    }

    @Test
    fun gatewayArgUrutanUserDomainPassword() {
        assertEquals(
            "g:gw.corp.id,u:kal,d:CORP,p:s3cret",
            RdpUri.gatewayArg(
                KzGateway(host = "gw.corp.id", username = "kal", password = "s3cret", domain = "CORP"),
            ),
        )
    }

    @Test
    fun gatewayArgLewatiFieldKosong() {
        assertEquals(
            "g:gw.corp.id",
            RdpUri.gatewayArg(KzGateway(host = "gw.corp.id", username = "  ", password = "", domain = null)),
        )
    }

    @Test
    fun gatewayMasukQueryParams() {
        val p = params(simple(), RdpOptions(gateway = KzGateway(host = "gw.corp.id", port = 8443)))
        assertEquals("g:gw.corp.id:8443", p["gateway"])
    }

    // ---------------------------------------------------------------- streaming, latency & security

    @Test
    fun ultraLowLatencyProfileMenghasilkanFlagAsyncDanMatikanDekorasi() {
        val opts = RdpOptions().withStreamProfile(KzStreamProfile.ULTRA_LOW_LATENCY)
        val p = params(simple(), opts)
        assertEquals("AVC420", p["gfx"])
        assertEquals("24", p["bpp"])
        assertEquals("+", p["async-update"])
        assertEquals("+", p["async-channels"])
        assertEquals("+", p["fonts"])
        assertEquals("-", p["wallpaper"])
        assertEquals("-", p["window-drag"])
        assertEquals("-", p["menu-anims"])
        assertEquals("-", p["themes"])
    }

    @Test
    fun securityDanSessionHardeningFlagsMasukKeQueryParams() {
        val opts = RdpOptions(
            securityProtocol = KzSecurityProtocol.NLA,
            tlsSecLevel = 0,
            consoleAdmin = true,
            restrictedAdmin = true,
            remoteProgram = "powershell.exe",
            remoteWorkDir = "C:\\Users\\Administrator",
            compressionLevel = 2,
        )
        val p = params(simple(), opts)
        assertEquals("nla", p["sec"])
        assertEquals("seclevel:0", p["tls"])
        assertTrue(p.containsKey("admin"))
        assertTrue(p.containsKey("restricted-admin"))
        assertEquals("powershell.exe", p["shell"])
        assertEquals("C:\\Users\\Administrator", p["shell-dir"])
        assertEquals("2", p["compression-level"])
    }

    @Test
    fun pcConnectModeMengirimAdminDanMemblokirFiturEksklusifRdp() {
        val opts = RdpOptions(
            pcConnectMode = true,
            dynamicResolution = true,
            localDrive = true,
            camera = true,
            microphone = true,
            remoteProgram = "cmd.exe",
            gateway = KzGateway(host = "gw.corp.id"),
        ).withPcStreamEngine(KzPcStreamEngine.DIRECT_GAME_ULTRA)
        val p = params(simple(), opts)
        assertTrue(p.containsKey("admin"))
        assertFalse(p.containsKey("dynamic-resolution"))
        assertFalse(p.containsKey("drive"))
        assertFalse(p.containsKey("dvc"))
        assertFalse(p.containsKey("microphone"))
        assertFalse(p.containsKey("shell"))
        assertFalse(p.containsKey("gateway"))
    }
}
