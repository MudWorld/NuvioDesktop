package com.nuvio.app.features.livetv

import java.io.BufferedInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Streams an XMLTV guide and keeps only programmes inside [windowStartMs]..[windowEndMs]
 * for the channel ids in [wantedChannelIds] (matched case-insensitively). Guides are
 * often 50+ MB, so nothing outside that window is held in memory.
 */
internal object XmltvParser {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val timeFormatNoZone = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(
        input: InputStream,
        wantedChannelIds: Set<String>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Map<String, List<LiveTVProgramme>> {
        val wanted = wantedChannelIds.mapTo(mutableSetOf()) { it.lowercase() }
        val result = mutableMapOf<String, MutableList<LiveTVProgramme>>()
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val reader = factory.createXMLStreamReader(maybeGunzip(input), "UTF-8")
        try {
            var channel: String? = null
            var start = 0L
            var end = 0L
            var title: String? = null
            var description: String? = null
            var inProgramme = false
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "programme" -> {
                            val channelId = reader.getAttributeValue(null, "channel")?.lowercase()
                            start = parseTime(reader.getAttributeValue(null, "start"))
                            end = parseTime(reader.getAttributeValue(null, "stop"))
                            inProgramme = channelId != null &&
                                channelId in wanted &&
                                end > windowStartMs &&
                                start < windowEndMs
                            channel = channelId
                            title = null
                            description = null
                        }

                        "title" -> if (inProgramme && title == null) title = reader.elementText.trim()
                        "desc" -> if (inProgramme && description == null) description = reader.elementText.trim()
                    }

                    XMLStreamConstants.END_ELEMENT -> if (reader.localName == "programme") {
                        val channelId = channel
                        val programmeTitle = title
                        if (inProgramme && channelId != null && !programmeTitle.isNullOrBlank() && end > start) {
                            result.getOrPut(channelId) { mutableListOf() } += LiveTVProgramme(
                                title = programmeTitle,
                                startEpochMs = start,
                                endEpochMs = end,
                                description = description?.takeIf { it.isNotBlank() },
                            )
                        }
                        inProgramme = false
                    }
                }
            }
        } finally {
            reader.close()
        }
        return result.mapValues { (_, programmes) -> programmes.sortedBy { it.startEpochMs } }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
    }

    internal fun parseTime(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.length < 14) return 0L
        return runCatching {
            if (text.length > 14) {
                OffsetDateTime.parse(text.replace(Regex("\\s+"), " "), timeFormat).toInstant().toEpochMilli()
            } else {
                java.time.LocalDateTime.parse(text, timeFormatNoZone)
                    .atOffset(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }
        }.getOrDefault(0L)
    }
}
