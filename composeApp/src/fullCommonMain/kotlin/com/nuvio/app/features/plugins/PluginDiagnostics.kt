package com.nuvio.app.features.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_diag_clear
import nuvio.composeapp.generated.resources.plugins_diag_empty
import nuvio.composeapp.generated.resources.plugins_diag_no_streams
import nuvio.composeapp.generated.resources.plugins_diag_streams
import nuvio.composeapp.generated.resources.plugins_diag_test_all
import nuvio.composeapp.generated.resources.plugins_diag_testing
import nuvio.composeapp.generated.resources.plugins_diag_title
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

/** One scraper execution, kept in memory so users can see which plugins work. */
data class PluginRunRecord(
    val scraperId: String,
    val scraperName: String,
    val target: String,
    val streamCount: Int,
    val error: String?,
    val durationMs: Long,
)

object PluginDiagnostics {
    private const val MaxRecords = 50

    private val _records = MutableStateFlow<List<PluginRunRecord>>(emptyList())
    val records: StateFlow<List<PluginRunRecord>> = _records.asStateFlow()

    internal suspend fun <T> track(
        scraper: PluginScraper,
        target: String,
        block: suspend () -> Result<List<T>>,
    ): Result<List<T>> {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        val record = PluginRunRecord(
            scraperId = scraper.id,
            scraperName = scraper.name,
            target = target,
            streamCount = result.getOrNull()?.size ?: 0,
            error = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "Error" },
            durationMs = mark.elapsedNow().inWholeMilliseconds,
        )
        _records.update { (listOf(record) + it).take(MaxRecords) }
        return result
    }

    fun clear() {
        _records.value = emptyList()
    }
}

@Composable
internal fun PluginDiagnosticsSection(scrapers: List<PluginScraper>) {
    val records by PluginDiagnostics.records.collectAsState()
    val scope = rememberCoroutineScope()
    var testingAll by remember { mutableStateOf(false) }

    NuvioSectionLabel(stringResource(Res.string.plugins_diag_title))
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !testingAll && scrapers.any { it.enabled },
                onClick = {
                    testingAll = true
                    scope.launch {
                        scrapers.filter { it.enabled }.forEach { PluginRepository.testScraper(it.id) }
                        testingAll = false
                    }
                },
            ) {
                Text(
                    stringResource(if (testingAll) Res.string.plugins_diag_testing else Res.string.plugins_diag_test_all),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (records.isNotEmpty()) {
                TextButton(onClick = PluginDiagnostics::clear) {
                    Text(stringResource(Res.string.plugins_diag_clear))
                }
            }
        }

        if (records.isEmpty()) {
            Text(
                text = stringResource(Res.string.plugins_diag_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@NuvioSurfaceCard
        }

        Spacer(modifier = Modifier.height(4.dp))
        records.forEach { record ->
            val (icon, tint) = when {
                record.error != null -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
                record.streamCount == 0 -> Icons.Rounded.RemoveCircleOutline to Color(0xFFE0A030)
                else -> Icons.Rounded.CheckCircle to Color(0xFF3FB950)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(icon, contentDescription = null, tint = tint)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${record.scraperName} • ${record.target}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = record.error
                            ?: if (record.streamCount == 0) {
                                stringResource(Res.string.plugins_diag_no_streams)
                            } else {
                                stringResource(Res.string.plugins_diag_streams, record.streamCount)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (record.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${record.durationMs} ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
