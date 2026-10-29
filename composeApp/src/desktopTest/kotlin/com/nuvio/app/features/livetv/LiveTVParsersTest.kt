package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveTVParsersTest {
    private val playlist = """
        #EXTM3U url-tvg="https://example.com/guide.xml.gz,https://example.com/other.xml"
        #EXTINF:-1 tvg-id="DasErste.de" tvg-logo="https://example.com/ard.png" group-title="News, Info",Das Erste HD
        #EXTVLCOPT:http-user-agent=Mozilla/5.0
        #EXTVLCOPT:http-referrer=https://example.com/
        https://example.com/ard.m3u8
        #EXTINF:-1,ZDF
        #EXTGRP:Public
        https://example.com/zdf.m3u8
        #EXTINF:-1 group-title="Music",YouTube Channel
        https://www.youtube.com/watch?v=abc
        #EXTINF:-1,Duplicate
        https://example.com/ard.m3u8
    """.trimIndent()

    @Test
    fun parsesChannelsGroupsHeadersAndGuideUrl() {
        val result = M3UParser.parse(playlist, "https://example.com/list.m3u")

        assertEquals("https://example.com/guide.xml.gz", result.epgUrl)
        assertEquals(2, result.channels.size)

        val ard = result.channels[0]
        assertEquals("Das Erste HD", ard.name)
        assertEquals("News, Info", ard.group)
        assertEquals("DasErste.de", ard.tvgId)
        assertEquals("https://example.com/ard.png", ard.logoUrl)
        assertEquals("Mozilla/5.0", ard.headers["User-Agent"])
        assertEquals("https://example.com/", ard.headers["Referer"])

        val zdf = result.channels[1]
        assertEquals("ZDF", zdf.name)
        assertEquals("Public", zdf.group)
        assertNull(zdf.logoUrl)
        assertEquals(emptyMap(), zdf.headers)
    }

    @Test
    fun xmltvKeepsOnlyWantedChannelsInsideWindow() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="DasErste.de">
                <title lang="de">Tagesschau</title><desc>Nachrichten</desc>
              </programme>
              <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="daserste.de">
                <title>Mittagsmagazin</title>
              </programme>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="Other.de">
                <title>Ignored</title>
              </programme>
              <programme start="20260105120000 +0000" stop="20260105130000 +0000" channel="DasErste.de">
                <title>Too late</title>
              </programme>
            </tv>
        """.trimIndent()
        val start = XmltvParser.parseTime("20260101110000 +0000")
        val end = XmltvParser.parseTime("20260102110000 +0000")

        val guide = XmltvParser.parse(xml.byteInputStream(), setOf("DasErste.de"), start, end)

        assertEquals(setOf("daserste.de"), guide.keys)
        assertEquals(listOf("Tagesschau", "Mittagsmagazin"), guide.getValue("daserste.de").map { it.title })
        assertEquals("Nachrichten", guide.getValue("daserste.de").first().description)
    }
}
