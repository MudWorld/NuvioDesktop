package com.nuvio.app.features.collection

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * The Collection Templates of the tvOS app (`CollectionTemplatesFlowSheet` in its
 * SettingsView.swift), ported 1:1: same folders, sources, images, presentation
 * styles and `templateID` / `templateVersion`, so a collection made here is
 * identical to one made on the Apple TV.
 */
enum class CollectionTemplate(
    val title: String,
    val subtitle: String,
    val emoji: String,
) {
    StreamingServices("Streaming Services", "Netflix, Prime Video, Disney+, Max, Crunchyroll and more", "📺"),
    StudiosAndFranchises("Studios & Franchises", "Production houses and cinematic universes", "🎬"),
    DiscoverByGenre("Discover by Genre", "Browse popular movies and series by genre", "🎭"),
    AsianFilmAndSeries("Asian Film & Series", "Korean, Chinese & Japanese dramas, Asian cinema, KissKH & MKV", "🥢"),
    ;

    suspend fun payload(): JsonObject = when (this) {
        StreamingServices -> StreamingServicesTemplate.payload(StreamingServicesTemplate.resolveLogoUrls())
        StudiosAndFranchises -> StudiosFranchisesTemplate.payload()
        DiscoverByGenre -> DiscoverGenresTemplate.payload()
        AsianFilmAndSeries -> AsianFilmAndSeriesTemplate.payload()
    }
}

// region JSON helpers (mirror the Swift [String: Any] payloads)

private fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { (key, value) -> key to value.toJson() })

private fun Any?.toJson(): JsonElement = when (this) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJson() })
    else -> error("Unsupported template value: $this")
}

private fun uuid(): String {
    val hex = "0123456789ABCDEF"
    return buildString {
        repeat(32) { index ->
            if (index == 8 || index == 12 || index == 16 || index == 20) append('-')
            append(hex[Random.nextInt(16)])
        }
    }
}

private fun tmdbImageUrl(path: String): String =
    if (path.startsWith("https://")) path else "https://image.tmdb.org/t/p/w500$path"

private fun tmdbBackdropUrl(path: String): String = "https://image.tmdb.org/t/p/w1280$path"

private fun rowsCollection(templateId: String, version: Int, title: String, folders: List<JsonObject>) = obj(
    "id" to uuid(),
    "templateID" to templateId,
    "templateVersion" to version,
    "title" to title,
    "pinToTop" to false,
    "focusGlowEnabled" to true,
    "viewMode" to "ROWS",
    "showAllTab" to false,
    "folders" to folders,
)

private fun tmdbSource(
    title: String,
    mediaType: String,
    sortBy: String,
    filters: JsonObject? = null,
    sourceType: String = "DISCOVER",
    tmdbId: Int? = null,
) = obj(
    "provider" to "tmdb",
    "tmdbSourceType" to sourceType,
    "title" to title,
    "mediaType" to mediaType,
    "sortBy" to sortBy,
    "tmdbId" to tmdbId,
    "filters" to filters,
)

// endregion

object StreamingServicesTemplate {
    data class Service(
        val name: String,
        val providerId: Int,
        val networkId: Int,
        val fallbackLogoPath: String,
        val backdropPath: String,
    )

    val services = listOf(
        Service("Netflix", 8, 213, "/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg", "/aVvRQJ2Ckhlym4uh0YGc166CUoP.jpg"),
        Service("Prime Video", 9, 1024, "/pvske1MyAoymrs5bguRfVqYiM9a.jpg", "/JYgqp8g2kI3SEus9XBDSHukfBN.jpg"),
        Service("Disney+", 337, 2739, "/97yvRBw1GzX7fXprcF80er19ot.jpg", "/14QbnygCuTO0vl7CAFmPf1fgZfV.jpg"),
        Service("Max", 1899, 3186, "/jbe4gVSfRlbPTdESXhEKpornsfu.jpg", "/577eXC8wFQT0eUrJcgznSiFPRmk.jpg"),
        Service("Apple TV+", 350, 2552, "/2E03IAZsX4ZaUqM7tXlctEPMGWS.jpg", "/uTWhbLc7Bj4qNSdW3ZvZKL8cOHv.jpg"),
        Service("Hulu", 15, 453, "/bxBlRPEPpMVDc4jMhSrTf2339DW.jpg", "/q3pCsNvJ7CmdJUz2sJEEUY3pOPC.jpg"),
        Service("Paramount+", 531, 4330, "/h5DcR0J2EESLitnhR8xLG1QymTE.jpg", "/zQCOimbHIq5BrLHThidw2bThZem.jpg"),
        Service("Peacock", 386, 3353, "/2aGrp1xw3qhwCYvNGAJZPdjfeeX.jpg", "/obtdxPgmfykYwVnvuYXC5f2xKlQ.jpg"),
        Service(
            "Crunchyroll",
            283,
            1112,
            "https://upload.wikimedia.org/wikipedia/commons/0/08/Crunchyroll_Logo.png",
            "/1RgPyOhN4DRs225BGTlHJqCudII.jpg",
        ),
    )

    val fallbackLogoUrls: Map<Int, String>
        get() = services.associate { it.providerId to tmdbImageUrl(it.fallbackLogoPath) }

    private val json = Json { ignoreUnknownKeys = true }

    /** Same lookup as tvOS: provider logos, then network wordmarks, Crunchyroll stays on its fixed logo. */
    suspend fun resolveLogoUrls(): Map<Int, String> = coroutineScope {
        val resolved = fallbackLogoUrls.toMutableMap()
        val apiKey = TmdbSettingsRepository.effectiveApiKey().trim()
        if (apiKey.isEmpty()) return@coroutineScope resolved

        runCatching {
            val body = json.parseToJsonElement(
                httpGetText("https://api.themoviedb.org/3/watch/providers/movie?api_key=$apiKey"),
            )
            body.jsonObject["results"]?.jsonArray?.forEach { element ->
                val provider = element.jsonObject
                val id = provider["provider_id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val path = provider["logo_path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                if (services.any { it.providerId == id }) resolved[id] = tmdbImageUrl(path)
            }
        }

        services.map { service ->
            async {
                service.providerId to runCatching {
                    json.parseToJsonElement(
                        httpGetText("https://api.themoviedb.org/3/network/${service.networkId}?api_key=$apiKey"),
                    ).jsonObject["logo_path"]?.jsonPrimitive?.contentOrNull?.let(::tmdbImageUrl)
                }.getOrNull()
            }
        }.forEach { deferred ->
            val (providerId, logo) = deferred.await()
            if (logo != null) resolved[providerId] = logo
        }
        services.firstOrNull { it.providerId == 283 }?.let { resolved[it.providerId] = tmdbImageUrl(it.fallbackLogoPath) }
        resolved
    }

    fun payload(logoUrls: Map<Int, String>): JsonObject {
        val folders = services.map { service ->
            val logo = logoUrls[service.providerId] ?: tmdbImageUrl(service.fallbackLogoPath)
            val filters = obj("withWatchProviders" to service.providerId.toString(), "watchRegion" to "US")
            obj(
                "id" to uuid(),
                "title" to service.name,
                "coverImageUrl" to logo,
                "titleLogoUrl" to logo,
                "heroBackdropUrl" to tmdbBackdropUrl(service.backdropPath),
                "presentationStyle" to "STREAMING_SERVICE",
                "tileShape" to "LANDSCAPE",
                "hideTitle" to true,
                "focusGifEnabled" to false,
                "sources" to listOf(
                    tmdbSource("Movies • Popular", "movie", "popularity.desc", filters),
                    tmdbSource("Series • Popular", "tv", "popularity.desc", filters),
                    tmdbSource("Recent Movies", "movie", "primary_release_date.desc", filters),
                    tmdbSource("Recent Shows", "tv", "first_air_date.desc", filters),
                ),
            )
        }
        return rowsCollection("streaming-services", 6, "Streaming Services", folders)
    }
}

private object StudiosFranchisesTemplate {
    fun payload(): JsonObject = rowsCollection(
        templateId = "studios-franchises",
        version = 3,
        title = "Studios & Franchises",
        folders = listOf(
            company("A24", 41077, "/1ZXsGaFPgrgS6ZZGS37AqD5uU12.png", "/wjwMC7u3xWKkrronolBqsIy4L0L.jpg"),
            brand(
                "HBO",
                "/tuomPhY2UtuPTqqFnKMVHvSb724.png",
                "/577eXC8wFQT0eUrJcgznSiFPRmk.jpg",
                fourCatalogSources("COMPANY", 3268, "NETWORK", 49),
            ),
            company("Pixar", 3, "/1TjvGVDMYsj6JBxOAkUHpPEwLf7.png", "/8sSKdEmlmqF4kJUd28SqthXC4yZ.jpg"),
            company("Warner Bros.", 174, "/zhD3hhtKB5qyv7ZeL4uLpNxgMVU.png", "/cu3lhUReOdqFAo5K1jesoftwiBj.jpg"),
            company("Universal", 33, "/8lvHyhjr8oUKOOy2dKXoALWKdp0.png", "/sSIzzVhhLfgLKVBcAUv0X6cLYz9.jpg"),
            company("Marvel", 420, "/hUzeosd33nzE5MCNsZxCGEKTXaQ.png", "/qeQJx07rK2xm8SD2sJxFKhE7gs0.jpg"),
            company("DC", 9993, "/2Tc1P3Ac8M479naPp1kYT3izLS5.png", "/rWYtghaUJSDvQm4jmXiCPXBHUdQ.jpg"),
        ),
    )

    private fun company(title: String, companyId: Int, logoPath: String, backdropPath: String) =
        brand(title, logoPath, backdropPath, fourCatalogSources("COMPANY", companyId, "COMPANY", companyId))

    private fun brand(title: String, logoPath: String, backdropPath: String, sources: List<JsonObject>): JsonObject {
        val logo = tmdbImageUrl(logoPath)
        return obj(
            "id" to uuid(),
            "title" to title,
            "coverImageUrl" to logo,
            "titleLogoUrl" to logo,
            "heroBackdropUrl" to tmdbBackdropUrl(backdropPath),
            "presentationStyle" to "STUDIO_FRANCHISE",
            "tileShape" to "LANDSCAPE",
            "hideTitle" to true,
            "focusGifEnabled" to false,
            "sources" to sources,
        )
    }

    private fun fourCatalogSources(movieType: String, movieId: Int, seriesType: String, seriesId: Int) = listOf(
        tmdbSource("Movies • Popular", "movie", "popularity.desc", sourceType = movieType, tmdbId = movieId),
        tmdbSource("Series • Popular", "tv", "popularity.desc", sourceType = seriesType, tmdbId = seriesId),
        tmdbSource("Recent Movies", "movie", "primary_release_date.desc", sourceType = movieType, tmdbId = movieId),
        tmdbSource("Recent Shows", "tv", "first_air_date.desc", sourceType = seriesType, tmdbId = seriesId),
    )
}

private object DiscoverGenresTemplate {
    private data class GenreGroup(
        val title: String,
        val emoji: String,
        val movieGenres: String?,
        val seriesGenres: String?,
        val seriesKeywords: String? = null,
        val backdropPath: String,
    )

    private val groups = listOf(
        GenreGroup("Action & Adventure", "💥", "28|12", "10759", backdropPath = "/sSIzzVhhLfgLKVBcAUv0X6cLYz9.jpg"),
        GenreGroup("Animation", "🎨", "16", "16", backdropPath = "/1RgPyOhN4DRs225BGTlHJqCudII.jpg"),
        GenreGroup("Comedy", "😂", "35", "35", backdropPath = "/xWBiXclrRmTggQHMRsIn84YHavs.jpg"),
        GenreGroup("Crime", "🕵️", "80", "80", backdropPath = "/qO55CD8tgVL1T4WKn6zYFFiD6lL.jpg"),
        GenreGroup("Documentary", "🎥", "99", "99", backdropPath = "/eCP3PAiu442zkJWczdLdvALePNK.jpg"),
        GenreGroup("Drama", "🎭", "18", "18", backdropPath = "/Af907x5h9W1wVis8XrSd7ynTWuy.jpg"),
        GenreGroup("Family", "👨‍👩‍👧‍👦", "10751", "10751", backdropPath = "/kxQiIJ4gVcD3K6o14MJ72p5yRcE.jpg"),
        GenreGroup("Horror", "👻", "27", null, "315058", "/rZfmzpixLKLR3Hg2u0WgC7XLFl8.jpg"),
        GenreGroup("Mystery & Thriller", "🔎", "9648|53", "9648", backdropPath = "/flxau5Iu7bChQHsESqvGZ3FQRaI.jpg"),
        GenreGroup("Romance", "❤️", "10749", null, "9840", "/1oKLEA9JOhvaBwLpqjROisvWMy7.jpg"),
        GenreGroup("Sci-Fi & Fantasy", "🚀", "878|14", "10765", backdropPath = "/qeQJx07rK2xm8SD2sJxFKhE7gs0.jpg"),
        GenreGroup("War & History", "⚔️", "10752|36", "10768", backdropPath = "/cu3lhUReOdqFAo5K1jesoftwiBj.jpg"),
    )

    fun payload(): JsonObject =
        rowsCollection("discover-genres", 3, "Discover by Genre", groups.map(::folder))

    /** Source order matches tvOS: movies popular, series popular, recent movies, recent shows. */
    private fun folder(group: GenreGroup): JsonObject {
        val sources = mutableListOf<JsonObject>()
        group.movieGenres?.let { genres ->
            val filters = obj("withGenres" to genres)
            sources += tmdbSource("Movies • Popular", "movie", "popularity.desc", filters)
            sources += tmdbSource("Recent Movies", "movie", "primary_release_date.desc", filters)
        }
        val seriesFilters = when {
            group.seriesGenres != null -> obj("withGenres" to group.seriesGenres)
            group.seriesKeywords != null -> obj("withKeywords" to group.seriesKeywords)
            else -> null
        }
        if (seriesFilters != null) {
            sources.add(minOf(1, sources.size), tmdbSource("Series • Popular", "tv", "popularity.desc", seriesFilters))
            sources += tmdbSource("Recent Shows", "tv", "first_air_date.desc", seriesFilters)
        }
        return obj(
            "id" to uuid(),
            "title" to group.title,
            "coverEmoji" to group.emoji,
            "heroBackdropUrl" to tmdbBackdropUrl(group.backdropPath),
            "tileShape" to "SQUARE",
            "hideTitle" to false,
            "focusGifEnabled" to false,
            "sources" to sources,
        )
    }
}

private object AsianFilmAndSeriesTemplate {
    fun payload(): JsonObject = rowsCollection(
        templateId = "asian-film-series",
        version = 1,
        title = "Asian Film & Series",
        folders = listOf(
            folder(
                "KissKH", "💋", "/oaGvjB0DvdurWhqAuTLYV3t4b2o.jpg",
                addon("kisskh", "series", "kisskh-drama", "KissKH • Asian Dramas"),
                addon("kisskh", "movie", "kisskh-movies", "KissKH • Asian Movies"),
                tmdbSource("K-Drama • Popular", "tv", "popularity.desc", obj("withOriginalLanguage" to "ko")),
                tmdbSource("C-Drama • Popular", "tv", "popularity.desc", obj("withOriginalLanguage" to "zh")),
                tmdbSource("Recent Asian Dramas", "tv", "first_air_date.desc", obj("withOriginalLanguage" to "ko|zh|ja|th")),
            ),
            folder(
                "MKV Asian Hub", "🎬", "/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg",
                addon("mkv", "series", "mkv-drama", "MKVDrama • Series"),
                addon("mkv", "movie", "mkv-movies", "MKV • Movies"),
                tmdbSource("Asian Movies • Popular", "movie", "popularity.desc", obj("withOriginalLanguage" to "ko|ja|zh|th|hk")),
                tmdbSource("Asian Series • Popular", "tv", "popularity.desc", obj("withOriginalLanguage" to "ko|ja|zh|th|hk")),
                tmdbSource("Recent Asian Movies", "movie", "primary_release_date.desc", obj("withOriginalLanguage" to "ko|ja|zh|th|hk")),
            ),
            folder(
                "Asian OTT & Streaming", "📺", "/577eXC8wFQT0eUrJcgznSiFPRmk.jpg",
                tmdbSource("Viki • Popular Series", "tv", "popularity.desc", obj("withWatchProviders" to "344", "watchRegion" to "US")),
                tmdbSource("iQIYI • Asian Series", "tv", "popularity.desc", obj("withWatchProviders" to "584", "watchRegion" to "US")),
                tmdbSource("Kocowa • K-Dramas", "tv", "popularity.desc", obj("withWatchProviders" to "455", "watchRegion" to "US")),
                tmdbSource(
                    "Asian OTT Movies", "movie", "popularity.desc",
                    obj("withOriginalLanguage" to "ko|zh|ja", "withWatchProviders" to "344|584|455", "watchRegion" to "US"),
                ),
            ),
            fourSourceFolder(
                "Korean Drama & Film", "🇰🇷", "/oaGvjB0DvdurWhqAuTLYV3t4b2o.jpg",
                series = obj("withOriginalLanguage" to "ko", "withOriginCountry" to "KR"),
                movies = obj("withOriginalLanguage" to "ko", "withOriginCountry" to "KR"),
                labels = listOf("K-Drama • Popular", "Korean Movies • Popular", "Recent K-Dramas", "Recent Korean Movies"),
            ),
            fourSourceFolder(
                "Chinese Drama & Film", "🇨🇳", "/qeQJx07rK2xm8SD2sJxFKhE7gs0.jpg",
                series = obj("withOriginalLanguage" to "zh", "withOriginCountry" to "CN"),
                movies = obj("withOriginalLanguage" to "zh"),
                labels = listOf("C-Drama • Popular", "Chinese Movies • Popular", "Recent C-Dramas", "Recent Chinese Movies"),
            ),
            fourSourceFolder(
                "Anime & Japanese Drama", "🇯🇵", "/1RgPyOhN4DRs225BGTlHJqCudII.jpg",
                series = obj("withOriginalLanguage" to "ja", "withOriginCountry" to "JP"),
                movies = obj("withOriginalLanguage" to "ja"),
                labels = listOf("Japanese Series • Popular", "Japanese Movies • Popular", "Recent Japanese Shows", "Recent Japanese Movies"),
            ),
            asianAction(),
        ),
    )

    private fun addon(addonId: String, type: String, catalogId: String, title: String) = obj(
        "provider" to "addon",
        "addonId" to addonId,
        "type" to type,
        "catalogId" to catalogId,
        "title" to title,
    )

    private fun folder(title: String, emoji: String, backdropPath: String, vararg sources: JsonObject) = obj(
        "id" to uuid(),
        "title" to title,
        "coverEmoji" to emoji,
        "heroBackdropUrl" to tmdbBackdropUrl(backdropPath),
        "tileShape" to "LANDSCAPE",
        "hideTitle" to false,
        "focusGifEnabled" to false,
        "sources" to sources.toList(),
    )

    /** Series popular, movies popular, recent series, recent movies, as in tvOS. */
    private fun fourSourceFolder(
        title: String,
        emoji: String,
        backdropPath: String,
        series: JsonObject,
        movies: JsonObject,
        labels: List<String>,
    ) = folder(
        title, emoji, backdropPath,
        tmdbSource(labels[0], "tv", "popularity.desc", series),
        tmdbSource(labels[1], "movie", "popularity.desc", movies),
        tmdbSource(labels[2], "tv", "first_air_date.desc", series),
        tmdbSource(labels[3], "movie", "primary_release_date.desc", movies),
    )

    private fun asianAction(): JsonObject {
        val movies = obj("withOriginalLanguage" to "zh|ko|ja|th|id|hk", "withGenres" to "28")
        val series = obj("withOriginalLanguage" to "zh|ko|ja|th|id|hk", "withGenres" to "10759")
        return folder(
            "Asian Action & Martial Arts", "🥋", "/sSIzzVhhLfgLKVBcAUv0X6cLYz9.jpg",
            tmdbSource("Action & Martial Arts Movies", "movie", "popularity.desc", movies),
            tmdbSource("Asian Action Series", "tv", "popularity.desc", series),
            tmdbSource("Recent Action Movies", "movie", "primary_release_date.desc", movies),
            tmdbSource("Recent Action Shows", "tv", "first_air_date.desc", series),
        )
    }
}
