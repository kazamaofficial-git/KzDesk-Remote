package com.kazamatech.kzdesk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji parser murni SoundCloud. Semua fixture meniru bentuk jawaban asli
 * soundcloud.com / api-v2 tanpa memuat datanya penuh.
 */
class SoundCloudParseTest {
    private val clientId = "aB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW"

    @Test
    fun `client_id diambil dari pola script`() {
        val html = """<script>window.__sc_options={"client_id":"$clientId"};</script>"""
        assertEquals(clientId, SoundCloudParse.extractClientId(html))
    }

    @Test
    fun `client_id diambil dari pola query`() {
        val js = "apiUrl:\"https://api-v2.soundcloud.com\",client_id=$clientId,env:"
        assertEquals(clientId, SoundCloudParse.extractClientId(js))
    }

    @Test
    fun `tanpa client_id menghasilkan null`() {
        assertNull(SoundCloudParse.extractClientId("<html>tidak ada apa-apa</html>"))
    }

    @Test
    fun `daftar src script terbaca`() {
        val html = """<script crossorigin src="https://a-v2.sndcdn.com/assets/app-1.js"></script>
            <script src="/assets/vendor-2.js"></script>"""
        val urls = SoundCloudParse.scriptUrls(html)
        assertEquals(2, urls.size)
        assertEquals("https://a-v2.sndcdn.com/assets/app-1.js", urls[0])
        assertEquals("https://soundcloud.com/assets/vendor-2.js", SoundCloudParse.absoluteUrl(urls[1]))
    }

    @Test
    fun `hasil pencarian diparsing dan entri rusak dilewati`() {
        val json = """
        {"collection":[
            {"track":{"kind":"track","id":101,"title":"Lagu Satu","duration":210000,
                "permalink_url":"https://soundcloud.com/a/one",
                "user":{"username":"artis_satu"}}},
            {"track":{"id":0,"title":"rusak tanpa id"}},
            {"track":{"kind":"track","id":102,"title":"Lagu Dua","duration":95000,
                "permalink_url":"https://soundcloud.com/b/two",
                "user":{"username":"artis_dua"}}}
        ]}
        """.trimIndent()
        val tracks = SoundCloudParse.parseSearch(json)
        assertEquals(2, tracks.size)
        assertEquals(101L, tracks[0].id)
        assertEquals("Lagu Satu", tracks[0].title)
        assertEquals("artis_satu", tracks[0].artist)
        assertEquals(210000L, tracks[0].durationMs)
        assertEquals(102L, tracks[1].id)
    }

    @Test
    fun `json pencarian rusak menghasilkan daftar kosong`() {
        assertTrue(SoundCloudParse.parseSearch("bukan json").isEmpty())
        assertTrue(SoundCloudParse.parseSearch("{}").isEmpty())
    }

    @Test
    fun `transcoder progresif mp3 dipilih mengalahkan hls`() {
        val json = """
        {"media":{"transcodings":[
            {"url":"https://api-v2.soundcloud.com/media/hls/1/stream",
             "format":{"protocol":"hls","mime_type":"audio/ogg; codecs=\"opus\""}},
            {"url":"https://api-v2.soundcloud.com/media/progressive/1/stream",
             "format":{"protocol":"progressive","mime_type":"audio/mpeg"}}
        ]}}
        """.trimIndent()
        assertEquals(
            "https://api-v2.soundcloud.com/media/progressive/1/stream",
            SoundCloudParse.parseTranscodingUrl(json),
        )
    }

    @Test
    fun `tanpa transcoder progresif menghasilkan null`() {
        val json = """
        {"media":{"transcodings":[
            {"url":"https://api-v2.soundcloud.com/media/hls/1/stream",
             "format":{"protocol":"hls","mime_type":"audio/ogg"}}
        ]}}
        """.trimIndent()
        assertNull(SoundCloudParse.parseTranscodingUrl(json))
        assertNull(SoundCloudParse.parseTranscodingUrl("{}"))
    }

    @Test
    fun `jawaban transcoder diparsing ke url cdn`() {
        val json = """{"url":"https://cf-media.sndcdn.com/xyz.128.mp3/abc"}"""
        assertEquals("https://cf-media.sndcdn.com/xyz.128.mp3/abc", SoundCloudParse.parseCdnUrl(json))
        assertNull(SoundCloudParse.parseCdnUrl("""{"url":""}"""))
    }
}
