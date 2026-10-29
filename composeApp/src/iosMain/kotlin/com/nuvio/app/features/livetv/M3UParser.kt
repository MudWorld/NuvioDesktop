package com.nuvio.app.features.livetv

internal object M3UParser {
    data class Result(
        val channels: List<LiveTVChannel>,
        val epgUrl: String?,
    )

    private val attributePattern = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private const val UngroupedName = "Other"

    fun parse(text: String, playlistUrl: String): Result {
        val channels = mutableListOf<LiveTVChannel>()
        val seenIds = mutableSetOf<String>()
        var epgUrl: String? = null

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingGroup: String? = null
        val pendingHeaders = mutableMapOf<String, String>()

        fun resetPending() {
            pendingName = null
            pendingAttributes = emptyMap()
            pendingGroup = null
            pendingHeaders.clear()
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = attributes(line)
                    epgUrl = (attributes["url-tvg"] ?: attributes["x-tvg-url"])
                        ?.split(',')
                        ?.firstOrNull()
                        ?.trim()
                        ?.takeIf { it.startsWith("http", ignoreCase = true) }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    resetPending()
                    val comma = displayNameComma(line)
                    pendingAttributes = attributes(if (comma >= 0) line.substring(0, comma) else line)
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "http-user-agent" -> pendingHeaders["User-Agent"] = value
                            "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                            "http-origin" -> pendingHeaders["Origin"] = value
                        }
                    }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName
                    val streamUrl = line
                    if (name != null && isPlayableUrl(streamUrl)) {
                        val id = liveTVChannelId(playlistUrl, streamUrl)
                        if (seenIds.add(id)) {
                            val attributes = pendingAttributes
                            channels += LiveTVChannel(
                                id = id,
                                name = name.ifBlank { attributes["tvg-name"].orEmpty().ifBlank { streamUrl } },
                                group = (attributes["group-title"] ?: pendingGroup)
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: UngroupedName,
                                logoUrl = attributes["tvg-logo"]?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) },
                                streamUrl = streamUrl,
                                playlistUrl = playlistUrl,
                                tvgId = attributes["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() },
                                tvgName = attributes["tvg-name"]?.trim()?.takeIf { it.isNotEmpty() },
                                headers = pendingHeaders.toMap(),
                            )
                        }
                    }
                    resetPending()
                }
            }
        }
        return Result(channels = channels, epgUrl = epgUrl)
    }

    private fun attributes(line: String): Map<String, String> =
        attributePattern.findAll(line)
            .associate { match -> match.groupValues[1].lowercase() to match.groupValues[2] }

    /** The display name follows the last comma that is outside quoted attribute values. */
    private fun displayNameComma(line: String): Int {
        var inQuotes = false
        var lastComma = -1
        line.forEachIndexed { index, char ->
            when (char) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) lastComma = index
            }
        }
        return lastComma
    }

    private fun isPlayableUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtmp") || lower.startsWith("rtsp"))) {
            return false
        }
        return "youtube.com/" !in lower && "youtu.be/" !in lower
    }
}
