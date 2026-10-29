package com.nuvio.app.features.livetv

/**
 * Scans an XMLTV guide and keeps only programmes inside [windowStartMs]..[windowEndMs]
 * for the channel ids in [wantedChannelIds] (matched case-insensitively).
 *
 * iOS has no streaming StAX reader, so this is a small string scanner for the
 * `<programme start stop channel><title/><desc/></programme>` subset of XMLTV.
 */
internal object XmltvParser {
    private val attributePattern = Regex("""([A-Za-z_:-]+)\s*=\s*"([^"]*)"""")
    private val timePattern = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-])(\d{2})(\d{2}))?""")

    fun parse(
        text: String,
        wantedChannelIds: Set<String>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Map<String, List<LiveTVProgramme>> {
        val wanted = wantedChannelIds.mapTo(mutableSetOf()) { it.lowercase() }
        val result = mutableMapOf<String, MutableList<LiveTVProgramme>>()
        var cursor = 0
        while (true) {
            val open = text.indexOf("<programme", cursor)
            if (open < 0) break
            val tagEnd = text.indexOf('>', open)
            if (tagEnd < 0) break
            val close = text.indexOf("</programme>", tagEnd)
            if (close < 0) break
            cursor = close + 12

            val attributes = attributePattern.findAll(text.substring(open, tagEnd))
                .associate { it.groupValues[1] to it.groupValues[2] }
            val channelId = attributes["channel"]?.lowercase() ?: continue
            if (channelId !in wanted) continue
            val start = parseTime(attributes["start"])
            val end = parseTime(attributes["stop"])
            if (end <= start || end <= windowStartMs || start >= windowEndMs) continue

            val body = text.substring(tagEnd + 1, close)
            val title = element(body, "title")?.takeIf { it.isNotBlank() } ?: continue
            result.getOrPut(channelId) { mutableListOf() } += LiveTVProgramme(
                title = title,
                startEpochMs = start,
                endEpochMs = end,
                description = element(body, "desc")?.takeIf { it.isNotBlank() },
            )
        }
        return result.mapValues { (_, programmes) -> programmes.sortedBy { it.startEpochMs } }
    }

    /** Text of the first `<name …>…</name>` inside [body]. */
    private fun element(body: String, name: String): String? {
        val open = body.indexOf("<$name")
        if (open < 0) return null
        val contentStart = body.indexOf('>', open)
        if (contentStart < 0 || body[contentStart - 1] == '/') return null
        val close = body.indexOf("</$name>", contentStart)
        if (close < 0) return null
        return decodeEntities(body.substring(contentStart + 1, close).trim())
    }

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        return Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""").replace(value) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    code?.let { codePointToString(it) } ?: match.value
                }
            }
        }
    }

    private fun codePointToString(code: Int): String =
        if (code < 0x10000) {
            code.toChar().toString()
        } else {
            val offset = code - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }

    /** `20240131203000 +0100` → epoch milliseconds (no zone = UTC). */
    internal fun parseTime(value: String?): Long {
        val match = timePattern.find(value?.trim().orEmpty()) ?: return 0L
        val g = match.groupValues
        val days = daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
        var seconds = days * 86_400L + g[4].toInt() * 3_600L + g[5].toInt() * 60L + g[6].toInt()
        if (g[7].isNotEmpty()) {
            val offset = g[8].toInt() * 3_600L + g[9].toInt() * 60L
            seconds -= if (g[7] == "+") offset else -offset
        }
        return seconds * 1000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}
