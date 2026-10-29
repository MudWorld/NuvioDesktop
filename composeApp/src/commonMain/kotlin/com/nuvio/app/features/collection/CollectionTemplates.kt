package com.nuvio.app.features.collection

import kotlin.random.Random

/**
 * Ready-made collections ported from the tvOS app's Collection Templates flow
 * (Streaming Services, Studios & Franchises, Discover by Genre, Asian Film & Series).
 * Each template builds a fresh [Collection] with new ids so it can be added more than once.
 */
enum class CollectionTemplate(
    val title: String,
    val subtitle: String,
    val emoji: String,
) {
    StreamingServices("Streaming Services", "Netflix, Prime Video, Disney+, Max and more", "📺"),
    StudiosAndFranchises("Studios & Franchises", "Production houses and cinematic universes", "🎬"),
    DiscoverByGenre("Discover by Genre", "Browse popular movies and series by genre", "🎭"),
    AsianFilmAndSeries("Asian Film & Series", "Korean, Chinese & Japanese dramas, Asian cinema, KissKH & MKV", "🥢"),
    ;

    fun build(): Collection = when (this) {
        StreamingServices -> streamingServices()
        StudiosAndFranchises -> studiosAndFranchises()
        DiscoverByGenre -> discoverByGenre()
        AsianFilmAndSeries -> asianFilmAndSeries()
    }
}

private fun templateId(): String {
    val chars = "0123456789abcdef"
    return buildString {
        repeat(32) { index ->
            if (index == 8 || index == 12 || index == 16 || index == 20) append('-')
            append(chars[Random.nextInt(chars.length)])
        }
    }
}

private fun tmdbImage(path: String): String =
    if (path.startsWith("https://")) path else "https://image.tmdb.org/t/p/w500$path"

private fun tmdbBackdrop(path: String): String = "https://image.tmdb.org/t/p/w1280$path"

private fun rowsCollection(title: String, folders: List<CollectionFolder>) = Collection(
    id = templateId(),
    title = title,
    pinToTop = false,
    viewMode = FolderViewMode.ROWS.name,
    showAllTab = false,
    folders = folders,
)

private fun tmdbSource(
    title: String,
    mediaType: String,
    sortBy: String,
    sourceType: TmdbCollectionSourceType = TmdbCollectionSourceType.DISCOVER,
    tmdbId: Int? = null,
    filters: TmdbCollectionFilters? = null,
) = CollectionSource(
    provider = "tmdb",
    tmdbSourceType = sourceType.name,
    title = title,
    tmdbId = tmdbId,
    mediaType = mediaType,
    sortBy = sortBy,
    filters = filters,
)

private fun addonSource(addonId: String, type: String, catalogId: String, title: String) = CollectionSource(
    provider = "addon",
    addonId = addonId,
    type = type,
    catalogId = catalogId,
    title = title,
)

private fun popularAndRecent(
    movieFilters: TmdbCollectionFilters?,
    seriesFilters: TmdbCollectionFilters?,
    movieSourceType: TmdbCollectionSourceType = TmdbCollectionSourceType.DISCOVER,
    movieId: Int? = null,
    seriesSourceType: TmdbCollectionSourceType = TmdbCollectionSourceType.DISCOVER,
    seriesId: Int? = null,
): List<CollectionSource> = listOf(
    tmdbSource("Movies • Popular", "movie", "popularity.desc", movieSourceType, movieId, movieFilters),
    tmdbSource("Series • Popular", "tv", "popularity.desc", seriesSourceType, seriesId, seriesFilters),
    tmdbSource("Recent Movies", "movie", "primary_release_date.desc", movieSourceType, movieId, movieFilters),
    tmdbSource("Recent Shows", "tv", "first_air_date.desc", seriesSourceType, seriesId, seriesFilters),
)

private fun streamingServices(): Collection {
    data class Service(val name: String, val providerId: Int, val logoPath: String, val backdropPath: String)

    val services = listOf(
        Service("Netflix", 8, "/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg", "/aVvRQJ2Ckhlym4uh0YGc166CUoP.jpg"),
        Service("Prime Video", 9, "/pvske1MyAoymrs5bguRfVqYiM9a.jpg", "/JYgqp8g2kI3SEus9XBDSHukfBN.jpg"),
        Service("Disney+", 337, "/97yvRBw1GzX7fXprcF80er19ot.jpg", "/14QbnygCuTO0vl7CAFmPf1fgZfV.jpg"),
        Service("Max", 1899, "/jbe4gVSfRlbPTdESXhEKpornsfu.jpg", "/577eXC8wFQT0eUrJcgznSiFPRmk.jpg"),
        Service("Apple TV+", 350, "/2E03IAZsX4ZaUqM7tXlctEPMGWS.jpg", "/uTWhbLc7Bj4qNSdW3ZvZKL8cOHv.jpg"),
        Service("Hulu", 15, "/bxBlRPEPpMVDc4jMhSrTf2339DW.jpg", "/q3pCsNvJ7CmdJUz2sJEEUY3pOPC.jpg"),
        Service("Paramount+", 531, "/h5DcR0J2EESLitnhR8xLG1QymTE.jpg", "/zQCOimbHIq5BrLHThidw2bThZem.jpg"),
        Service("Peacock", 386, "/2aGrp1xw3qhwCYvNGAJZPdjfeeX.jpg", "/obtdxPgmfykYwVnvuYXC5f2xKlQ.jpg"),
        Service(
            "Crunchyroll",
            283,
            "https://upload.wikimedia.org/wikipedia/commons/0/08/Crunchyroll_Logo.png",
            "/1RgPyOhN4DRs225BGTlHJqCudII.jpg",
        ),
    )
    val folders = services.map { service ->
        val logo = tmdbImage(service.logoPath)
        val filters = TmdbCollectionFilters(
            withWatchProviders = service.providerId.toString(),
            watchRegion = "US",
        )
        CollectionFolder(
            id = templateId(),
            title = service.name,
            coverImageUrl = logo,
            titleLogoUrl = logo,
            heroBackdropUrl = tmdbBackdrop(service.backdropPath),
            tileShape = "LANDSCAPE",
            hideTitle = true,
            focusGifEnabled = false,
            sources = popularAndRecent(movieFilters = filters, seriesFilters = filters),
        )
    }
    return rowsCollection("Streaming Services", folders)
}

private fun studiosAndFranchises(): Collection {
    fun brand(title: String, logoPath: String, backdropPath: String, sources: List<CollectionSource>) =
        CollectionFolder(
            id = templateId(),
            title = title,
            coverImageUrl = tmdbImage(logoPath),
            titleLogoUrl = tmdbImage(logoPath),
            heroBackdropUrl = tmdbBackdrop(backdropPath),
            tileShape = "LANDSCAPE",
            hideTitle = true,
            focusGifEnabled = false,
            sources = sources,
        )

    fun company(title: String, companyId: Int, logoPath: String, backdropPath: String) = brand(
        title = title,
        logoPath = logoPath,
        backdropPath = backdropPath,
        sources = popularAndRecent(
            movieFilters = null,
            seriesFilters = null,
            movieSourceType = TmdbCollectionSourceType.COMPANY,
            movieId = companyId,
            seriesSourceType = TmdbCollectionSourceType.COMPANY,
            seriesId = companyId,
        ),
    )

    val folders = listOf(
        company("A24", 41077, "/1ZXsGaFPgrgS6ZZGS37AqD5uU12.png", "/wjwMC7u3xWKkrronolBqsIy4L0L.jpg"),
        brand(
            title = "HBO",
            logoPath = "/tuomPhY2UtuPTqqFnKMVHvSb724.png",
            backdropPath = "/577eXC8wFQT0eUrJcgznSiFPRmk.jpg",
            sources = popularAndRecent(
                movieFilters = null,
                seriesFilters = null,
                movieSourceType = TmdbCollectionSourceType.COMPANY,
                movieId = 3268,
                seriesSourceType = TmdbCollectionSourceType.NETWORK,
                seriesId = 49,
            ),
        ),
        company("Pixar", 3, "/1TjvGVDMYsj6JBxOAkUHpPEwLf7.png", "/8sSKdEmlmqF4kJUd28SqthXC4yZ.jpg"),
        company("Warner Bros.", 174, "/zhD3hhtKB5qyv7ZeL4uLpNxgMVU.png", "/cu3lhUReOdqFAo5K1jesoftwiBj.jpg"),
        company("Universal", 33, "/8lvHyhjr8oUKOOy2dKXoALWKdp0.png", "/sSIzzVhhLfgLKVBcAUv0X6cLYz9.jpg"),
        company("Marvel", 420, "/hUzeosd33nzE5MCNsZxCGEKTXaQ.png", "/qeQJx07rK2xm8SD2sJxFKhE7gs0.jpg"),
        company("DC", 9993, "/2Tc1P3Ac8M479naPp1kYT3izLS5.png", "/rWYtghaUJSDvQm4jmXiCPXBHUdQ.jpg"),
    )
    return rowsCollection("Studios & Franchises", folders)
}

private fun discoverByGenre(): Collection {
    data class GenreGroup(
        val title: String,
        val emoji: String,
        val movieGenres: String?,
        val seriesGenres: String?,
        val seriesKeywords: String? = null,
        val backdropPath: String,
    )

    val groups = listOf(
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
    val folders = groups.map { group ->
        val movieFilters = group.movieGenres?.let { TmdbCollectionFilters(withGenres = it) }
        val seriesFilters = when {
            group.seriesGenres != null -> TmdbCollectionFilters(withGenres = group.seriesGenres)
            group.seriesKeywords != null -> TmdbCollectionFilters(withKeywords = group.seriesKeywords)
            else -> null
        }
        val sources = buildList {
            if (movieFilters != null) {
                add(tmdbSource("Movies • Popular", "movie", "popularity.desc", filters = movieFilters))
            }
            if (seriesFilters != null) {
                add(tmdbSource("Series • Popular", "tv", "popularity.desc", filters = seriesFilters))
            }
            if (movieFilters != null) {
                add(tmdbSource("Recent Movies", "movie", "primary_release_date.desc", filters = movieFilters))
            }
            if (seriesFilters != null) {
                add(tmdbSource("Recent Shows", "tv", "first_air_date.desc", filters = seriesFilters))
            }
        }
        CollectionFolder(
            id = templateId(),
            title = group.title,
            coverEmoji = group.emoji,
            heroBackdropUrl = tmdbBackdrop(group.backdropPath),
            tileShape = "SQUARE",
            hideTitle = false,
            focusGifEnabled = false,
            sources = sources,
        )
    }
    return rowsCollection("Discover by Genre", folders)
}

private fun asianFilmAndSeries(): Collection {
    fun folder(title: String, emoji: String, backdropPath: String, sources: List<CollectionSource>) =
        CollectionFolder(
            id = templateId(),
            title = title,
            coverEmoji = emoji,
            heroBackdropUrl = tmdbBackdrop(backdropPath),
            tileShape = "LANDSCAPE",
            hideTitle = false,
            focusGifEnabled = false,
            sources = sources,
        )

    fun lang(language: String, country: String? = null, genres: String? = null) =
        TmdbCollectionFilters(withOriginalLanguage = language, withOriginCountry = country, withGenres = genres)

    fun provider(ids: String, language: String? = null) =
        TmdbCollectionFilters(withWatchProviders = ids, watchRegion = "US", withOriginalLanguage = language)

    val asianLanguages = "ko|ja|zh|th|hk"
    val actionLanguages = "zh|ko|ja|th|id|hk"
    val folders = listOf(
        folder(
            "KissKH", "💋", "/oaGvjB0DvdurWhqAuTLYV3t4b2o.jpg",
            listOf(
                addonSource("kisskh", "series", "kisskh-drama", "KissKH • Asian Dramas"),
                addonSource("kisskh", "movie", "kisskh-movies", "KissKH • Asian Movies"),
                tmdbSource("K-Drama • Popular", "tv", "popularity.desc", filters = lang("ko")),
                tmdbSource("C-Drama • Popular", "tv", "popularity.desc", filters = lang("zh")),
                tmdbSource("Recent Asian Dramas", "tv", "first_air_date.desc", filters = lang("ko|zh|ja|th")),
            ),
        ),
        folder(
            "MKV Asian Hub", "🎬", "/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg",
            listOf(
                addonSource("mkv", "series", "mkv-drama", "MKVDrama • Series"),
                addonSource("mkv", "movie", "mkv-movies", "MKV • Movies"),
                tmdbSource("Asian Movies • Popular", "movie", "popularity.desc", filters = lang(asianLanguages)),
                tmdbSource("Asian Series • Popular", "tv", "popularity.desc", filters = lang(asianLanguages)),
                tmdbSource("Recent Asian Movies", "movie", "primary_release_date.desc", filters = lang(asianLanguages)),
            ),
        ),
        folder(
            "Asian OTT & Streaming", "📺", "/577eXC8wFQT0eUrJcgznSiFPRmk.jpg",
            listOf(
                tmdbSource("Viki • Popular Series", "tv", "popularity.desc", filters = provider("344")),
                tmdbSource("iQIYI • Asian Series", "tv", "popularity.desc", filters = provider("584")),
                tmdbSource("Kocowa • K-Dramas", "tv", "popularity.desc", filters = provider("455")),
                tmdbSource("Asian OTT Movies", "movie", "popularity.desc", filters = provider("344|584|455", "ko|zh|ja")),
            ),
        ),
        folder(
            "Korean Drama & Film", "🇰🇷", "/oaGvjB0DvdurWhqAuTLYV3t4b2o.jpg",
            listOf(
                tmdbSource("K-Drama • Popular", "tv", "popularity.desc", filters = lang("ko", "KR")),
                tmdbSource("Korean Movies • Popular", "movie", "popularity.desc", filters = lang("ko", "KR")),
                tmdbSource("Recent K-Dramas", "tv", "first_air_date.desc", filters = lang("ko", "KR")),
                tmdbSource("Recent Korean Movies", "movie", "primary_release_date.desc", filters = lang("ko", "KR")),
            ),
        ),
        folder(
            "Chinese Drama & Film", "🇨🇳", "/qeQJx07rK2xm8SD2sJxFKhE7gs0.jpg",
            listOf(
                tmdbSource("C-Drama • Popular", "tv", "popularity.desc", filters = lang("zh", "CN")),
                tmdbSource("Chinese Movies • Popular", "movie", "popularity.desc", filters = lang("zh")),
                tmdbSource("Recent C-Dramas", "tv", "first_air_date.desc", filters = lang("zh", "CN")),
                tmdbSource("Recent Chinese Movies", "movie", "primary_release_date.desc", filters = lang("zh")),
            ),
        ),
        folder(
            "Anime & Japanese Drama", "🇯🇵", "/1RgPyOhN4DRs225BGTlHJqCudII.jpg",
            listOf(
                tmdbSource("Japanese Series • Popular", "tv", "popularity.desc", filters = lang("ja", "JP")),
                tmdbSource("Japanese Movies • Popular", "movie", "popularity.desc", filters = lang("ja")),
                tmdbSource("Recent Japanese Shows", "tv", "first_air_date.desc", filters = lang("ja", "JP")),
                tmdbSource("Recent Japanese Movies", "movie", "primary_release_date.desc", filters = lang("ja")),
            ),
        ),
        folder(
            "Asian Action & Martial Arts", "🥋", "/sSIzzVhhLfgLKVBcAUv0X6cLYz9.jpg",
            listOf(
                tmdbSource("Action & Martial Arts Movies", "movie", "popularity.desc", filters = lang(actionLanguages, genres = "28")),
                tmdbSource("Asian Action Series", "tv", "popularity.desc", filters = lang(actionLanguages, genres = "10759")),
                tmdbSource("Recent Action Movies", "movie", "primary_release_date.desc", filters = lang(actionLanguages, genres = "28")),
                tmdbSource("Recent Action Shows", "tv", "first_air_date.desc", filters = lang(actionLanguages, genres = "10759")),
            ),
        ),
    )
    return rowsCollection("Asian Film & Series", folders)
}
