package ru.moex.tradinglab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import ru.moex.tradinglab.data.local.HistoryCoverageEntity
import ru.moex.tradinglab.data.local.MarketDataDao
import ru.moex.tradinglab.data.local.MarketInstrumentEntity
import ru.moex.tradinglab.data.remote.IssInstrument
import ru.moex.tradinglab.data.remote.MoexMarket
import ru.moex.tradinglab.data.remote.MoexFuturesSpecificationRepository
import ru.moex.tradinglab.data.sync.MoexMarketDataSync
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChartScreen(
    dao: MarketDataDao,
    sync: MoexMarketDataSync,
    futuresSpecifications: MoexFuturesSpecificationRepository,
    onOpenDiagnostics: () -> Unit
) {
    val instruments by dao.observeInstruments().collectAsState(initial = emptyList())
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MarketInstrumentEntity?>(null) }
    var interval by remember { mutableStateOf(24) }
    var isLoadingHistory by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val candlesFlow = remember(selected?.instrumentId, interval) {
        dao.observeCandles(selected?.instrumentId.orEmpty(), interval)
    }
    val candles by candlesFlow.collectAsState(initial = emptyList())
    val coverageFlow = remember(selected?.instrumentId, interval) {
        dao.observeCoverage(selected?.instrumentId.orEmpty(), interval)
    }
    val coverage by coverageFlow.collectAsState(initial = emptyList())

    fun loadHistory(instrument: MarketInstrumentEntity, force: Boolean = false) {
        if (isLoadingHistory || (!force && candles.isNotEmpty())) return
        scope.launch {
            isLoadingHistory = true
            loadError = null
            try {
                sync.syncInstrumentHistoryIncrementally(
                    instrument = instrument.toIssInstrument(),
                    intervalMinutes = interval
                )
            } catch (error: Exception) {
                loadError = error.message ?: error.javaClass.simpleName
            } finally {
                isLoadingHistory = false
            }
        }
    }

    LaunchedEffect(selected?.instrumentId, interval) {
        val instrument = selected
        if (instrument != null && candles.isEmpty()) {
            loadHistory(instrument)
        }
    }

    val matching = remember(instruments, search) {
        val query = search.trim()
        if (query.isEmpty()) {
            instruments.take(8)
        } else {
            instruments.filter {
                it.secId.contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.shortName.orEmpty().contains(query, ignoreCase = true)
            }.take(10)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("График", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Свечи и объёмы из локальной истории MOEX, сохранённой в Room.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
                label = { Text("Поиск инструмента") },
                placeholder = { Text("Например, SBER или IMOEX") }
            )
            if (matching.isEmpty()) {
                Text(
                    if (instruments.isEmpty()) "Каталог инструментов ещё не загружен." else "Инструменты не найдены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        if (selected == null) {
            items(matching, key = { it.instrumentId }) { instrument ->
                OutlinedButton(
                    onClick = {
                        selected = instrument
                        loadError = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "${instrument.secId} · ${instrument.shortName ?: instrument.name} · ${instrument.boardId ?: instrument.market}",
                        maxLines = 1
                    )
                }
            }
        }

        item {
            selected?.let { instrument ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "${instrument.secId} · ${instrument.name}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${instrument.market} / ${instrument.boardId ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = {
                            selected = null
                            loadError = null
                        }) {
                            Text("Выбрать другой инструмент")
                        }
                        Text("Таймфрейм", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 10, 60, 24).forEach { value ->
                                OutlinedButton(
                                    onClick = {
                                        interval = value
                                        loadError = null
                                    },
                                    enabled = !isLoadingHistory
                                ) {
                                    Text(if (value == 24) "1д" else "${value}м")
                                }
                            }
                        }
                        if (isLoadingHistory) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator()
                                Text("Загружаем свечи из MOEX ISS…")
                            }
                        } else if (candles.isEmpty()) {
                            Text(
                                "Для выбранного таймфрейма история пока не сохранена. Выполняется загрузка.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = { loadHistory(instrument, force = true) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Повторить загрузку истории") }
                        } else {
                            OutlinedButton(
                                onClick = { loadHistory(instrument, force = true) },
                                enabled = !isLoadingHistory,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Обновить историю") }
                        }
                        loadError?.let {
                            Text(
                                "Не удалось загрузить свечи: $it",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        HistoryCoverageDetails(
                            coverage = coverage.firstOrNull(),
                            storedCandleCount = candles.size
                        )
                        OutlinedButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Загрузка и диагностика данных")
                        }
                        CandleChart(
                            instrumentId = instrument.instrumentId,
                            intervalMinutes = interval,
                            candles = candles,
                            modifier = Modifier.fillMaxWidth()
                        )

                        PreTradeRiskCard(
                            instrument = instrument,
                            futuresSpecificationRepository = futuresSpecifications,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } ?: run {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Выберите инструмент", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "После выбора приложение автоматически запросит историю свечей MOEX для выбранного таймфрейма.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = onOpenDiagnostics) {
                            Text("Открыть загрузку истории")
                        }
                    }
                }
            }
        }
    }
}

private fun MarketInstrumentEntity.toIssInstrument(): IssInstrument {
    val fieldsType: Type = object : TypeToken<Map<String, String?>>() {}.type
    val fields = rawFieldsJson?.let {
        runCatching { Gson().fromJson<Map<String, String?>>(it, fieldsType) }.getOrNull()
    }.orEmpty()
    return IssInstrument(
        secId = secId,
        name = name,
        shortName = shortName,
        isin = isin,
        boardId = boardId,
        lotSize = lotSize,
        currency = currency,
        market = MoexMarket.valueOf(market),
        source = source,
        rawFields = fields
    )
}

@Composable
private fun HistoryCoverageDetails(
    coverage: HistoryCoverageEntity?,
    storedCandleCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Состояние истории", style = MaterialTheme.typography.titleSmall)
        Text("Свечей сохранено в Room: $storedCandleCount", style = MaterialTheme.typography.bodySmall)
        if (coverage == null) {
            Text(
                "Загрузка для этого таймфрейма ещё не зафиксирована.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        Text("Источник: ${coverage.source}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Диапазон: ${coverage.firstCandleBegin ?: "нет данных"} — ${coverage.lastCandleBegin ?: "нет данных"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Пагинация: ${chartPaginationLabel(coverage.paginationStatus)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Страниц: ${coverage.pageCount} · размер страницы: ${coverage.pageSize}",
            style = MaterialTheme.typography.bodySmall
        )
        Text("Пропущено некорректных строк: ${coverage.skippedRows}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Пропуски свечей: ${coverage.gapCount?.toString() ?: "не оценивались — нет проверенного расписания"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Актуальность: ${chartFreshnessLabel(coverage.freshnessStatus)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Получено: ${formatChartTimestamp(coverage.fetchedAtEpochMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun chartPaginationLabel(value: String): String = when (value) {
    "COMPLETE" -> "получена завершающая страница"
    "PAGE_LIMIT_REACHED" -> "достигнут лимит страниц — история может быть неполной"
    "PAGINATION_STALLED" -> "пагинация остановилась"
    else -> value
}

internal fun chartFreshnessLabel(value: String): String = when (value) {
    "FRESH" -> "актуальные"
    "STALE" -> "устаревшие"
    "MARKET_CLOSED" -> "рынок закрыт"
    "WAITING_FOR_CANDLE_CLOSE" -> "ожидание закрытия свечи"
    "UNKNOWN" -> "неизвестна"
    else -> value
}

private fun formatChartTimestamp(epochMillis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
