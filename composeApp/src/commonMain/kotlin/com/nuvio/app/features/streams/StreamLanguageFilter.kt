package com.nuvio.app.features.streams

// WatchParty: hide streams by audio language. The German Providers plugins put the language into every stream name
// ("Huhu · Deutsch · voe.sx", "Aniworld · Japanisch, dt. UT · …"); addons usually say "German"/"GER"/"DE" or a flag.
enum class StreamLanguageFilter {
    ALL,
    GERMAN_AND_SUBTITLED,
    GERMAN_ONLY,
    ;

    companion object {
        fun fromStorage(value: String?): StreamLanguageFilter =
            entries.firstOrNull { it.name == value } ?: ALL
    }
}

object StreamLanguageClassifier {
    enum class Language { GERMAN, GERMAN_SUBTITLED, OTHER, UNKNOWN }

    private val subtitled = Regex(
        """dt\.\s*UT|\bOmU\b|ger(man)?[\s._-]*sub|deutsche?\s+untertitel|untertitel\s+deutsch""",
        RegexOption.IGNORE_CASE,
    )
    private val german = Regex("""deutsch|\bgerman\b|\bger\b|\bdeu\b""", RegexOption.IGNORE_CASE)

    // upper case only: a lower-case "de" is a word in French, Spanish and Dutch titles
    private val germanCode = Regex("""\bDE\b|🇩🇪""")
    private val other = Regex(
        """englisch|\benglish\b|\beng\b|französisch|\bfrench\b|spanisch|\bspanish\b|italienisch|\bitalian\b|""" +
            """japanisch|\bjapanese\b|türkisch|\bturkish\b|russisch|\brussian\b|\bOV\b|original|\bmulti\b|🇬🇧|🇺🇸""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(stream: StreamItem): Language {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" · ")
        return when {
            subtitled.containsMatchIn(text) -> Language.GERMAN_SUBTITLED
            german.containsMatchIn(text) || germanCode.containsMatchIn(text) -> Language.GERMAN
            other.containsMatchIn(text) -> Language.OTHER
            else -> Language.UNKNOWN
        }
    }

    // UNKNOWN stays visible for ALL only: an addon that names no language is not known to be German.
    fun keeps(stream: StreamItem, filter: StreamLanguageFilter): Boolean = when (filter) {
        StreamLanguageFilter.ALL -> true
        StreamLanguageFilter.GERMAN_AND_SUBTITLED -> classify(stream).let { it == Language.GERMAN || it == Language.GERMAN_SUBTITLED }
        StreamLanguageFilter.GERMAN_ONLY -> classify(stream) == Language.GERMAN
    }

    fun apply(group: AddonStreamGroup, filter: StreamLanguageFilter): AddonStreamGroup =
        if (filter == StreamLanguageFilter.ALL) group else group.copy(streams = group.streams.filter { keeps(it, filter) })
}
