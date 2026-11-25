package ru.moex.tradinglab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import ru.moex.tradinglab.data.local.CatalogSyncEntity
import ru.moex.tradinglab.data.local.HistoryCoverageEntity
import ru.moex.tradinglab.data.local.MarketDataDao
import ru.moex.tradinglab.data.local.MarketInstrumentEntity
import ru.moex.tradinglab.data.local.SecuritiesListingDao
import ru.moex.tradinglab.data.remote.HistoryCompleteness
import ru.moex.tradinglab.data.remote.IssInstrument
import ru.moex.tradinglab.data.remote.MoexMarket
import ru.moex.tradinglab.data.sync.MoexMarketDataSync
import ru.moex.tradinglab.data.sync.SecuritiesListingSync
import ru.moex.tradinglab.data.sync.SecuritiesListingSyncResult
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MarketDataDiagnosticsScreen(
    dao: MarketDataDao,
    sync: MoexMarketDataSync,
    securitiesListingDao: SecuritiesListingDao,
    securitiesListingSync: SecuritiesListingSync
) {
    val instruments by dao.observeInstruments().collectAsState(initial = emptyList())
    val catalogStates by dao.observeCatalogSync().collectAsState(initial = emptyList())
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val messages = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    suspend fun performCatalogLoad(market: MoexMarket) {
        if (busy[market.name] == true) return
        busy[market.name] = true
        messages.remove(market.name)
        try {
            val result = sync.syncCatalog(market)
            messages[market.name] = "Получено инструментов: ${result.instruments.size}. Страниц: ${result.pageCount}."
        } catch (error: Exception) {
            messages[market.name] = "Ошибка загрузки: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            busy[market.name] = false
        }
    }

    fun loadCatalog(market: MoexMarket) {
        scope.launch { performCatalogLoad(market) }
    }

    fun loadAllCatalogs() {
        scope.launch {
            MoexMarket.entries.forEach { market -> performCatalogLoad(market) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "MOEX Trading Lab",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Диагностика рыночных данных",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "Загрузка каталогов выполняется только на чтение. Полученные записи сохраняются локально в Room.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = { loadAllCatalogs() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Text("Обновить все каталоги")
                }
            }

            item(key = "securities-listing-snapshot") {
                SecuritiesListingSnapshotSection(
                    dao = securitiesListingDao,
                    sync = securitiesListingSync
                )
            }

            items(MoexMarket.entries.toList(), key = { it.name }) { market ->
                val saved = catalogStates.firstOrNull { it.market == market.name }
                val count = instruments.count { it.market == market.name }
                MarketCatalogCard(
                    market = market,
                    saved = saved,
                    instrumentCount = count,
                    isLoading = busy[market.name] == true,
                    message = messages[market.name],
                    onLoad = { loadCatalog(market) }
                )
            }

            item(key = "history-loader") {
                HistoryLoadingSection(instruments = instruments, dao = dao, sync = sync)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Что означает полнота", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "«Полная» означает, что ISS вернул страницу короче запрошенного размера. Достижение лимита страниц или остановка пагинации не считаются полной историей.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            text = "Успешная загрузка каталога не доказывает доступность свечей для каждого инструмента и таймфрейма. Эти данные проверяются отдельно.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Источник котировочных справочников: MOEX ISS · автоматическая отправка заявок не используется.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


@Composable
private fun SecuritiesListingSnapshotSection(
    dao: SecuritiesListingDao,
    sync: SecuritiesListingSync
) {
    val snapshots by dao.observeSnapshots().collectAsState(initial = emptyList())
    val latest = snapshots.firstOrNull()
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("Снимок допуска к торговым сессиям", style = MaterialTheme.typography.titleMedium)
            Text(
                "Публичный архив MOEX для акций. Сохраняются только пары SECID/BOARDID из вашего локального каталога. Это не календарь торгов и не доказательство наличия свечи.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        message = null
                        try {
                            message = when (val result = sync.syncLatestSnapshot()) {
                                SecuritiesListingSyncResult.NoShareCatalog ->
                                    "Сначала загрузите каталог акций MOEX."
                                is SecuritiesListingSyncResult.Saved ->
                                    "Снимок ${result.tradeDate}: сохранено ${result.storedEntries} строк; вне локального каталога ${result.filteredOutEntries}; исходных записей ${result.declaredRecords}; статус ${securitiesListingStatusLabel(result.status)}."
                            }
                        } catch (error: Exception) {
                            message = "Не удалось обновить снимок: ${error.message ?: error.javaClass.simpleName}. Предыдущий снимок сохранён."
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Загрузить текущий снимок")
                }
            }
            if (latest == null) {
                Text("Снимок ещё не загружался.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Дата торгов: ${latest.tradeDate}", style = MaterialTheme.typography.bodySmall)
                Text("Сохранено пар: ${latest.storedEntryCount}", style = MaterialTheme.typography.bodySmall)
                Text("Исключено вне каталога: ${latest.filteredOutEntryCount}", style = MaterialTheme.typography.bodySmall)
                Text("Всего записей в DBF: ${latest.declaredRecordCount}", style = MaterialTheme.typography.bodySmall)
                Text("Неизвестные флаги: ${latest.unknownFlagCount} · дубликаты: ${latest.duplicateRecordCount}", style = MaterialTheme.typography.bodySmall)
                Text("Статус: ${securitiesListingStatusLabel(latest.status)}", style = MaterialTheme.typography.bodySmall)
                Text("Получено: ${formatTimestamp(latest.fetchedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
                Text("SHA-256: ${latest.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun securitiesListingStatusLabel(status: String): String = when (status) {
    "COMPLETE" -> "полный разбор"
    "COMPLETE_WITH_DUPLICATES" -> "есть одинаковые дубликаты"
    "PARTIAL_FLAGS" -> "есть неизвестные значения флагов"
    "NO_LOCAL_MATCHES" -> "нет совпадений с локальным каталогом"
    else -> status
}

@Composable
private fun HistoryLoadingSection(
    instruments: List<MarketInstrumentEntity>,
    dao: MarketDataDao,
    sync: MoexMarketDataSync
) {
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MarketInstrumentEntity?>(null) }
    var interval by remember { mutableStateOf(24) }
    var fromDate by remember { mutableStateOf("2000-01-01") }
    var isLoading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val coverageFlow = remember(selected?.instrumentId, interval) {
        dao.observeCoverage(selected?.instrumentId.orEmpty(), interval)
    }
    val coverage by coverageFlow.collectAsState(initial = emptyList())
    val candlesFlow = remember(selected?.instrumentId, interval) {
        dao.observeCandles(selected?.instrumentId.orEmpty(), interval)
    }
    val candles by candlesFlow.collectAsState(initial = emptyList())
    val matching = remember(instruments, search) {
        val query = search.trim()
        if (query.isEmpty()) instruments.take(6) else instruments.filter {
            it.secId.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.shortName.orEmpty().contains(query, ignoreCase = true)
        }.take(8)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("История свечей", style = MaterialTheme.typography.titleLarge)
            Text(
                "Выберите инструмент из локального каталога MOEX, затем таймфрейм и начальную дату.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Поиск инструмента") },
                placeholder = { Text("Например, SBER или IMOEX") }
            )
            if (matching.isEmpty()) {
                Text(
                    if (instruments.isEmpty()) "Сначала загрузите каталог рынка." else "Инструменты не найдены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Найденные инструменты", style = MaterialTheme.typography.labelLarge)
                matching.forEach { item ->
                    OutlinedButton(
                        onClick = {
                            selected = item
                            resultMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${item.secId} · ${item.shortName ?: item.name} · ${item.boardId ?: item.market}",
                            maxLines = 1
                        )
                    }
                }
            }
            selected?.let { item ->
                Text(
                    "Выбран: ${item.secId} · ${item.name} · ${item.market}/${item.boardId ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text("Таймфрейм", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 10, 60, 24).forEach { value ->
                    OutlinedButton(
                        onClick = { interval = value },
                        enabled = !isLoading
                    ) {
                        Text(if (value == 24) "1д" else "${value}м")
                    }
                }
            }
            OutlinedTextField(
                value = fromDate,
                onValueChange = { fromDate = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Начало истории") },
                supportingText = { Text("Формат YYYY-MM-DD. По умолчанию — 2000-01-01.") }
            )
            Button(
                onClick = {
                    val instrument = selected ?: return@Button
                    if (fromDate.isNotBlank() && !fromDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                        resultMessage = "Проверьте дату: нужен формат YYYY-MM-DD."
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        resultMessage = null
                        try {
                            val result = sync.syncInstrumentHistoryIncrementally(
                                instrument = instrument.toIssInstrument(),
                                intervalMinutes = interval,
                                initialFrom = fromDate.trim().ifBlank { null }
                            )
                            resultMessage = "Получено свечей: ${result.candles.size}; страниц: ${result.pageCount}; пропущено строк: ${result.skippedRows}; статус: ${historyCompletenessLabel(result.completeness)}. Диапазон: ${result.firstBegin ?: "нет данных"} — ${result.lastBegin ?: "нет данных"}."
                        } catch (error: Exception) {
                            resultMessage = "Ошибка загрузки: ${error.message ?: error.javaClass.simpleName}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = selected != null && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Загрузить / обновить историю")
                }
            }
            Text(
                "За один запуск — до ${MoexMarketDataSyncHistoryLimit.MAX_CANDLES_PER_RUN} свечей (200 страниц по 500). Статус «достигнут лимит» означает, что история загружена не полностью.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            resultMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            val latest = coverage.firstOrNull()
            if (latest != null) {
                HistoryCoverageSummary(latest)
            }
            if (selected != null) {
                Text("График свечей", style = MaterialTheme.typography.titleMedium)
                CandleChart(
                    instrumentId = selected!!.instrumentId,
                    intervalMinutes = interval,
                    candles = candles,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HistoryCoverageSummary(item: HistoryCoverageEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Последняя сохранённая загрузка", style = MaterialTheme.typography.titleSmall)
        Text("Источник: ${item.source}", style = MaterialTheme.typography.bodySmall)
        Text("Страниц: ${item.pageCount} · размер страницы: ${item.pageSize}", style = MaterialTheme.typography.bodySmall)
        Text("Пропущено строк: ${item.skippedRows}", style = MaterialTheme.typography.bodySmall)
        Text("Пагинация: ${historyPaginationLabel(item.paginationStatus)}", style = MaterialTheme.typography.bodySmall)
        Text("Покрытие: ${item.firstCandleBegin ?: "нет свечей"} — ${item.lastCandleBegin ?: "нет свечей"}", style = MaterialTheme.typography.bodySmall)
        Text("Проверка пропусков: ${item.gapCount?.toString() ?: "не оценивалась"}", style = MaterialTheme.typography.bodySmall)
        Text("Актуальность: ${item.freshnessStatus}", style = MaterialTheme.typography.bodySmall)
        Text("Получено: ${formatTimestamp(item.fetchedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
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

private fun historyCompletenessLabel(value: HistoryCompleteness): String = when (value) {
    HistoryCompleteness.COMPLETE -> "полная пагинация"
    HistoryCompleteness.PAGE_LIMIT_REACHED -> "достигнут лимит страниц"
    HistoryCompleteness.PAGINATION_STALLED -> "пагинация остановилась"
}

private fun historyPaginationLabel(value: String): String = when (value) {
    "COMPLETE" -> "полная"
    "PAGE_LIMIT_REACHED" -> "достигнут лимит страниц"
    "PAGINATION_STALLED" -> "остановилась"
    else -> value
}

private object MoexIssTableLimits {
    const val PAGE_SIZE = 500
    const val MAX_PAGES = 200
}

private object MoexMarketDataSyncHistoryLimit {
    const val MAX_CANDLES_PER_RUN = MoexIssTableLimits.PAGE_SIZE * MoexIssTableLimits.MAX_PAGES
}

@Composable
private fun MarketCatalogCard(
    market: MoexMarket,
    saved: CatalogSyncEntity?,
    instrumentCount: Int,
    isLoading: Boolean,
    message: String?,
    onLoad: () -> Unit
) {
    val title = when (market) {
        MoexMarket.SHARES -> "Акции"
        MoexMarket.FUTURES -> "Фьючерсы"
        MoexMarket.INDICES -> "Индексы"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "В Room: $instrumentCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(onClick = onLoad, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Загрузить")
                    }
                }
            }
            if (saved == null) {
                Text(
                    text = "Каталог ещё не загружался",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
            } else {
                Text("Источник: ${saved.source}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                Text("Получено записей: ${saved.instrumentCount}", style = MaterialTheme.typography.bodySmall)
                Text("Страниц: ${saved.pageCount} · размер страницы: ${saved.pageSize}", style = MaterialTheme.typography.bodySmall)
                Text("Пропущено некорректных строк: ${saved.skippedRows}", style = MaterialTheme.typography.bodySmall)
                Text("Полнота: ${completenessLabel(saved.completeness)}", style = MaterialTheme.typography.bodySmall)
                Text("Последнее получение: ${formatTimestamp(saved.fetchedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
            }
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun completenessLabel(value: String): String = when (value) {
    "COMPLETE" -> "получена короткая завершающая страница"
    "PAGE_LIMIT_REACHED" -> "достигнут лимит страниц"
    "PAGINATION_STALLED" -> "пагинация остановилась"
    else -> value
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
