// iPad copy of the desktop Live TV screen: touch (long press), adaptive layout, no AWT.
package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual val liveTVSupported: Boolean = true

private sealed interface LiveTVFilter {
    data object All : LiveTVFilter
    data object Favorites : LiveTVFilter
    data object Recent : LiveTVFilter
    data object Hidden : LiveTVFilter
    data class Group(val name: String) : LiveTVFilter
}

@Composable
actual fun LiveTVScreen(
    modifier: Modifier,
    topChromePadding: Dp?,
    onPlay: (LiveTVPlayback) -> Unit,
) {
    LaunchedEffect(Unit) { LiveTVRepository.ensureLoaded() }

    val playlists by LiveTVRepository.playlists.collectAsState()
    val channels by LiveTVRepository.channels.collectAsState()
    val favoriteIds by LiveTVRepository.favoriteIds.collectAsState()
    val hiddenIds by LiveTVRepository.hiddenIds.collectAsState()
    val recentIds by LiveTVRepository.recentIds.collectAsState()
    val refreshing by LiveTVRepository.refreshingUrls.collectAsState()
    LiveTVRepository.guide.collectAsState().value // recompose when the guide arrives

    var filter by remember { mutableStateOf<LiveTVFilter>(LiveTVFilter.All) }
    var query by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(liveTVNowMs()) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = liveTVNowMs()
        }
    }

    val byId = remember(channels) { channels.associateBy { it.id } }
    val visible = remember(channels, hiddenIds) { channels.filterNot { it.id in hiddenIds } }
    val groups = remember(visible) { visible.map { it.group }.distinct() }
    val favorites = remember(favoriteIds, byId) { favoriteIds.mapNotNull(byId::get) }
    val recents = remember(recentIds, byId, hiddenIds) {
        recentIds.mapNotNull(byId::get).filterNot { it.id in hiddenIds }
    }
    val hidden = remember(channels, hiddenIds) { channels.filter { it.id in hiddenIds } }

    fun play(channel: LiveTVChannel, list: List<LiveTVChannel>) {
        LiveTVRepository.zapList = list
        val (current, _) = LiveTVRepository.nowAndNext(channel, now)
        onPlay(channel.toPlayback(nowPlaying = current?.title))
    }

    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .background(tokens.colors.background)
            .padding(top = topChromePadding ?: 0.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.isMetaPressed || event.isCtrlPressed) && event.key == Key.F) {
                    runCatching { searchFocus.requestFocus() }
                    true
                } else {
                    false
                }
            },
    ) {
        LiveTVHeader(
            query = query,
            onQueryChange = { query = it },
            searchFocus = searchFocus,
            selectMode = selectMode,
            onToggleSelect = { selectMode = !selectMode },
            refreshing = refreshing.isNotEmpty(),
            onRefresh = { LiveTVRepository.refreshAll() },
            onManagePlaylists = { showPlaylists = true },
            showActions = playlists.isNotEmpty(),
        )

        if (playlists.isEmpty()) {
            LiveTVEmptyState(onAdded = { filter = LiveTVFilter.All })
            return@Column
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Full iPad width: group sidebar; Split View / Slide Over: horizontal chips.
        val wide = maxWidth >= 600.dp
        Column(modifier = Modifier.fillMaxSize()) {
        if (!wide) {
            LiveTVFilterChips(
                filter = filter,
                onFilterChange = {
                    filter = it
                    query = ""
                },
                groups = groups,
                favoriteCount = favorites.size,
                recentCount = recents.size,
                hiddenCount = hidden.size,
            )
        }
        Row(modifier = Modifier.fillMaxSize()) {
            if (wide) {
                LiveTVFilterSidebar(
                    filter = filter,
                    onFilterChange = {
                        filter = it
                        query = ""
                    },
                    groups = groups,
                    favoriteCount = favorites.size,
                    recentCount = recents.size,
                    hiddenCount = hidden.size,
                    totalCount = visible.size,
                )
            }

            val trimmedQuery = query.trim()
            val gridState = rememberLazyGridState()
            LaunchedEffect(filter, trimmedQuery) { gridState.scrollToItem(0) }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = if (wide) 200.dp else 150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = if (wide) 12.dp else 16.dp,
                    end = if (wide) 24.dp else 16.dp,
                    top = 8.dp,
                    bottom = 48.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (selectMode) {
                    fullWidth {
                        Text(
                            text = stringResource(Res.string.livetv_select_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.warning,
                        )
                    }
                }

                val cardScope = ChannelCardActions(
                    favoriteIds = favoriteIds.toSet(),
                    hiddenIds = hiddenIds,
                    selectMode = selectMode,
                    now = now,
                )

                if (trimmedQuery.isNotEmpty()) {
                    val pool = if (filter == LiveTVFilter.Hidden) hidden else visible
                    val results = pool.filter { channel ->
                        channel.name.contains(trimmedQuery, ignoreCase = true) ||
                            channel.group.contains(trimmedQuery, ignoreCase = true)
                    }
                    section(
                        title = if (results.isEmpty()) null else "${results.size} × „$trimmedQuery“",
                        channels = results,
                        actions = cardScope,
                        onPlay = { play(it, results) },
                    )
                    if (results.isEmpty()) {
                        fullWidth { LiveTVNoResults(trimmedQuery) }
                    }
                    return@LazyVerticalGrid
                }

                when (val current = filter) {
                    LiveTVFilter.All -> {
                        if (favorites.isNotEmpty()) {
                            sectionTitle(Res.string.livetv_favorites)
                            section(null, favorites, cardScope) { play(it, favorites) }
                        }
                        if (recents.isNotEmpty()) {
                            sectionTitle(Res.string.livetv_recent)
                            section(null, recents, cardScope) { play(it, recents) }
                        }
                        groups.forEach { group ->
                            val groupChannels = visible.filter { it.group == group }
                            section(group, groupChannels, cardScope) { play(it, visible) }
                        }
                    }

                    LiveTVFilter.Favorites -> section(null, favorites, cardScope) { play(it, favorites) }
                    LiveTVFilter.Recent -> section(null, recents, cardScope) { play(it, recents) }
                    LiveTVFilter.Hidden -> section(null, hidden, cardScope) { play(it, hidden) }
                    is LiveTVFilter.Group -> {
                        val groupChannels = visible.filter { it.group == current.name }
                        section(null, groupChannels, cardScope) { play(it, groupChannels) }
                    }
                }
            }
        }
        }
        }
    }

    if (showPlaylists) {
        LiveTVPlaylistsDialog(onDismiss = { showPlaylists = false })
    }
}

private data class ChannelCardActions(
    val favoriteIds: Set<String>,
    val hiddenIds: Set<String>,
    val selectMode: Boolean,
    val now: Long,
)

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.sectionTitle(title: org.jetbrains.compose.resources.StringResource) {
    fullWidth {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.nuvio.colors.textPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private fun LazyGridScope.section(
    title: String?,
    channels: List<LiveTVChannel>,
    actions: ChannelCardActions,
    onPlay: (LiveTVChannel) -> Unit,
) {
    if (title != null) {
        fullWidth {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.nuvio.colors.textSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    items(channels, key = { "${title.orEmpty()}|${it.id}" }) { channel ->
        LiveTVChannelCard(
            channel = channel,
            isFavorite = channel.id in actions.favoriteIds,
            isHidden = channel.id in actions.hiddenIds,
            selectMode = actions.selectMode,
            now = actions.now,
            onPlay = { onPlay(channel) },
        )
    }
}

@Composable
private fun LiveTVHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    selectMode: Boolean,
    onToggleSelect: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onManagePlaylists: () -> Unit,
    showActions: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(30.dp))
        Text(
            text = stringResource(Res.string.livetv_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.textPrimary,
        )
        Spacer(Modifier.weight(1f))
        if (showActions) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = null)
                        }
                    }
                },
                placeholder = { Text(stringResource(Res.string.livetv_search)) },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 340.dp)
                    .focusRequester(searchFocus),
                shape = RoundedCornerShape(14.dp),
            )
            TextButton(onClick = onToggleSelect) {
                Text(
                    text = stringResource(if (selectMode) Res.string.livetv_done else Res.string.livetv_select),
                    color = if (selectMode) tokens.colors.warning else tokens.colors.textPrimary,
                )
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.livetv_refresh),
                    tint = if (refreshing) tokens.colors.textMuted else tokens.colors.textPrimary,
                )
            }
            IconButton(onClick = onManagePlaylists) {
                Icon(
                    Icons.Rounded.PlaylistAdd,
                    contentDescription = stringResource(Res.string.livetv_playlists),
                    tint = tokens.colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun LiveTVFilterSidebar(
    filter: LiveTVFilter,
    onFilterChange: (LiveTVFilter) -> Unit,
    groups: List<String>,
    favoriteCount: Int,
    recentCount: Int,
    hiddenCount: Int,
    totalCount: Int,
) {
    LazyColumn(
        modifier = Modifier
            .width(230.dp)
            .fillMaxHeight(),
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            FilterRow(stringResource(Res.string.livetv_all), totalCount, filter == LiveTVFilter.All) {
                onFilterChange(LiveTVFilter.All)
            }
        }
        item {
            FilterRow("★ " + stringResource(Res.string.livetv_favorites), favoriteCount, filter == LiveTVFilter.Favorites) {
                onFilterChange(LiveTVFilter.Favorites)
            }
        }
        item {
            FilterRow(stringResource(Res.string.livetv_recent), recentCount, filter == LiveTVFilter.Recent) {
                onFilterChange(LiveTVFilter.Recent)
            }
        }
        if (hiddenCount > 0) {
            item {
                FilterRow(stringResource(Res.string.livetv_hidden), hiddenCount, filter == LiveTVFilter.Hidden) {
                    onFilterChange(LiveTVFilter.Hidden)
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
        items(groups, key = { it }) { group ->
            FilterRow(group, null, filter == LiveTVFilter.Group(group)) {
                onFilterChange(LiveTVFilter.Group(group))
            }
        }
    }
}

@Composable
private fun LiveTVFilterChips(
    filter: LiveTVFilter,
    onFilterChange: (LiveTVFilter) -> Unit,
    groups: List<String>,
    favoriteCount: Int,
    recentCount: Int,
    hiddenCount: Int,
) {
    val tokens = MaterialTheme.nuvio
    val entries = buildList {
        add(LiveTVFilter.All to stringResource(Res.string.livetv_all))
        if (favoriteCount > 0) add(LiveTVFilter.Favorites to "★ " + stringResource(Res.string.livetv_favorites))
        if (recentCount > 0) add(LiveTVFilter.Recent to stringResource(Res.string.livetv_recent))
        if (hiddenCount > 0) add(LiveTVFilter.Hidden to stringResource(Res.string.livetv_hidden))
        groups.forEach { add(LiveTVFilter.Group(it) to it) }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries) { (entry, label) ->
            val selected = entry == filter
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) tokens.colors.accent.copy(alpha = 0.22f) else tokens.colors.surfaceCard)
                    .clickable { onFilterChange(entry) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun FilterRow(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> tokens.colors.accent.copy(alpha = 0.18f)
                    hovered -> tokens.colors.surfaceElevated
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(text = count.toString(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTVChannelCard(
    channel: LiveTVChannel,
    isFavorite: Boolean,
    isHidden: Boolean,
    selectMode: Boolean,
    now: Long,
    onPlay: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val (current, next) = remember(channel.id, now, LiveTVRepository.guide.value) {
        LiveTVRepository.nowAndNext(channel, now)
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (hovered) tokens.colors.surfaceElevated else tokens.colors.surfaceCard)
                .border(
                    width = if (selectMode || hovered) 2.dp else 1.dp,
                    color = when {
                        selectMode -> tokens.colors.warning.copy(alpha = 0.8f)
                        hovered -> tokens.colors.borderFocus
                        else -> tokens.colors.borderSubtle
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                .hoverable(interaction)
                .combinedClickable(
                    onClick = { if (selectMode) menuOpen = true else onPlay() },
                    onLongClick = { menuOpen = true },
                )
                .secondaryClick { menuOpen = true },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    NuvioAsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = tokens.colors.textMuted,
                    )
                }
                if (hovered && !selectMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isFavorite) Badge(Icons.Rounded.Star, Color(0xFFFFC94D))
                    if (isHidden) Badge(Icons.Rounded.VisibilityOff, Color.White)
                }
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current != null) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { current.progress(now) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = tokens.colors.accent,
                        trackColor = tokens.colors.borderSubtle,
                    )
                } else {
                    Text(
                        text = channel.group,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (next != null && hovered) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.livetv_next, formatTime(next.startEpochMs), next.title),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.livetv_play)) },
                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onPlay()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (isFavorite) Res.string.livetv_favorite_remove else Res.string.livetv_favorite_add)) },
                leadingIcon = { Icon(if (isFavorite) Icons.Rounded.StarBorder else Icons.Rounded.Star, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    LiveTVRepository.toggleFavorite(channel.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (isHidden) Res.string.livetv_unhide else Res.string.livetv_hide)) },
                leadingIcon = { Icon(if (isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    LiveTVRepository.toggleHidden(channel.id)
                },
            )
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun LiveTVNoResults(query: String) {
    Text(
        text = stringResource(Res.string.livetv_no_results, query),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.padding(top = 32.dp),
    )
}

@Composable
private fun LiveTVEmptyState(onAdded: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceCard)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.livetv_empty_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.livetv_empty_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            AddPlaylistForm(onAdded = onAdded)
        }
    }
}

@Composable
private fun AddPlaylistForm(onAdded: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                error = null
            },
            singleLine = true,
            label = { Text(stringResource(Res.string.livetv_playlist_url)) },
            placeholder = { Text("https://iptv-org.github.io/iptv/countries/de.m3u") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(Res.string.livetv_playlist_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(Res.string.livetv_legal),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = {
                    if (busy || url.isBlank()) return@Surface
                    busy = true
                    scope.launch {
                        val result = LiveTVRepository.addPlaylist(url, name)
                        busy = false
                        result
                            .onSuccess { count ->
                                NuvioToastController.show(getString(Res.string.livetv_playlist_added, count))
                                url = ""
                                name = ""
                                onAdded()
                            }
                            .onFailure { failure ->
                                error = getString(Res.string.livetv_playlist_error, failure.message ?: failure::class.simpleName.orEmpty())
                            }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = tokens.colors.accent,
                enabled = !busy && url.isNotBlank(),
            ) {
                Text(
                    text = stringResource(if (busy) Res.string.livetv_playlist_adding else Res.string.livetv_playlist_add),
                    color = tokens.colors.onAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveTVPlaylistsDialog(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val playlists by LiveTVRepository.playlists.collectAsState()
    val refreshing by LiveTVRepository.refreshingUrls.collectAsState()
    val guideLoading by LiveTVRepository.guideLoading.collectAsState()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceDialog)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.livetv_playlists),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (guideLoading) {
                    Text(stringResource(Res.string.livetv_epg_loading), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.livetv_close)) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(playlists, key = { it.url }) { playlist ->
                    PlaylistRow(playlist = playlist, refreshing = playlist.url in refreshing)
                }
            }

            Text(
                text = stringResource(Res.string.livetv_playlist_add),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            AddPlaylistForm(onAdded = {})
        }
    }
}

@Composable
private fun PlaylistRow(playlist: LiveTVPlaylist, refreshing: Boolean) {
    val tokens = MaterialTheme.nuvio
    var epgUrl by remember(playlist.url, playlist.epgUrl) { mutableStateOf(playlist.epgUrl.orEmpty()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surfaceCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
                Text(
                    text = stringResource(Res.string.livetv_channel_count, playlist.channelCount) + " • " + playlist.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { LiveTVRepository.refresh(playlist.url) }, enabled = !refreshing) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(Res.string.livetv_refresh))
            }
            IconButton(onClick = { LiveTVRepository.removePlaylist(playlist.url) }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.livetv_playlist_remove), tint = tokens.colors.danger)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.livetv_epg_url)) },
                supportingText = { Text(stringResource(Res.string.livetv_epg_hint)) },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { LiveTVRepository.setEpgUrl(playlist.url, epgUrl) },
                enabled = epgUrl.trim() != playlist.epgUrl.orEmpty(),
            ) {
                Text(stringResource(Res.string.livetv_save))
            }
        }
    }
}

private val timeFormatter = NSDateFormatter().apply { dateFormat = "HH:mm" }

private fun formatTime(epochMs: Long): String =
    timeFormatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

internal fun liveTVNowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
