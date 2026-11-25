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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.moex.tradinglab.analysis.BacktestConfig
import ru.moex.tradinglab.analysis.BacktestRunResult
import ru.moex.tradinglab.analysis.BacktestRunner
import ru.moex.tradinglab.data.local.BacktestHistoryRepository
import ru.moex.tradinglab.data.local.MarketDataDao
import ru.moex.tradinglab.data.local.MarketInstrumentEntity
import ru.moex.tradinglab.data.remote.IssInstrument
import ru.moex.tradinglab.data.remote.MoexMarket
import ru.moex.tradinglab.data.remote.MoexCalendarRepository
import ru.moex.tradinglab.data.sync.MoexDailyHistoryCoverageVerifier
import ru.moex.tradinglab.data.sync.MoexMarketDataSync
import ru.moex.tradinglab.data.sync.HistoryVerificationResult
import ru.moex.tradinglab.util.MoexMarketClock
import java.time.LocalDate
import java.util.Locale

@Composable
fun BacktestScreen(
    dao: MarketDataDao,
    sync: MoexMarketDataSync,
    calendar: MoexCalendarRepository
) {
    val verifier = remember(dao, calendar) { MoexDailyHistoryCoverageVerifier(dao, calendar) }
    val instruments by dao.observeInstruments().collectAsState(initial = emptyList())
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MarketInstrumentEntity?>(null) }
    var from by remember { mutableStateOf("2025-01-01") }
    var till by remember { mutableStateOf("") }
    var capital by remember { mutableStateOf("1000") }
    var riskPercent by remember { mutableStateOf("2") }
    var isRunning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BacktestRunResult?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val matching = remember(instruments, search) {
        val query = search.trim()
        if (query.isEmpty()) instruments.take(30)
        else instruments.filter {
            it.secId.contains(query, true) ||
                it.name.contains(query, true) ||
                it.shortName.orEmpty().contains(query, true)
        }.take(30)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Тестирование стратегии", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Исторический тест пробоя с подтверждением объёма. Сигнал по закрытой свече исполняется не раньше открытия следующей. Заявки брокеру не отправляются.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Найти инструмент") },
                placeholder = { Text("SBER, GAZP, IMOEX") }
            )
        }
        if (selected == null) {
            items(matching, key = { it.instrumentId }) { instrument ->
                OutlinedButton(
                    onClick = { selected = instrument; result = null; inputError = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${instrument.secId} · ${instrument.shortName ?: instrument.name} · ${instrument.market}")
                }
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${selected!!.secId} · ${selected!!.name}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("${selected!!.market} / ${selected!!.boardId ?: "борд не указан"}")
                        OutlinedButton(onClick = { selected = null; result = null }) {
                            Text("Выбрать другой инструмент")
                        }
                    }
                }
            }
        }
        item {
            Text("Таймфрейм")
            Text(
                "Пока доступен только дневной тест акций: для него реализована проверка расписания MOEX. Внутридневные интервалы и фьючерсы не запускаются, пока их календарные правила не подтверждены.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(24, 60, 10, 1).forEach { value ->
                    OutlinedButton(
                        onClick = { result = null },
                        enabled = !isRunning && value == 24
                    ) {
                        Text(if (value == 24) "1д" else "${value}м")
                    }
                }
            }
            OutlinedTextField(
                value = from,
                onValueChange = { from = it; result = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Начало (ГГГГ-ММ-ДД)") }
            )
            OutlinedTextField(
                value = till,
                onValueChange = { till = it; result = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Конец (ГГГГ-ММ-ДД)") },
                placeholder = { Text("Например, 2026-11-25") }
            )
        }
        item {
            Text("Параметры капитала и риска")
            OutlinedTextField(
                value = capital,
                onValueChange = { capital = it; result = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Начальный капитал, ₽") }
            )
            OutlinedTextField(
                value = riskPercent,
                onValueChange = { riskPercent = it; result = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Риск на сделку, %") }
            )
            Text(
                "Комиссия: 0,05% за сторону. Проскальзывание: 5 б.п. за сторону. Это редактируемые в коде базовые допущения, не тариф брокера.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Button(
                onClick = {
                    val instrument = selected
                    val startCapital = capital.replace(',', '.').toDoubleOrNull()
                    val risk = riskPercent.replace(',', '.').toDoubleOrNull()
                    if (instrument == null) {
                        inputError = "Сначала выберите инструмент"
                    } else if (till.isBlank() || from.length != 10 || till.length != 10) {
                        inputError = "Укажите обе даты в формате ГГГГ-ММ-ДД"
                    } else if (startCapital == null || risk == null || !startCapital.isFinite() ||
                        !risk.isFinite() || startCapital <= 0.0 || risk <= 0.0 || risk > 100.0
                    ) {
                        inputError = "Проверьте капитал и риск: капитал больше 0, риск от 0 до 100%"
                    } else {
                        val startDate = runCatching { LocalDate.parse(from) }.getOrNull()
                        val endDate = runCatching { LocalDate.parse(till) }.getOrNull()
                        if (instrument.market != "SHARES") {
                            inputError = "Этот тест пока поддерживает только акции."
                        } else if (startDate == null || endDate == null || endDate.isBefore(startDate) ||
                            endDate.isAfter(MoexMarketClock.today())
                        ) {
                            inputError = "Укажите корректный диапазон дат: конец не раньше начала и не позже сегодняшнего дня."
                        } else {
                            inputError = null
                            result = null
                            isRunning = true
                            scope.launch {
                                try {
                                    val issInstrument = IssInstrument(
                                        secId = instrument.secId,
                                        name = instrument.name,
                                        shortName = instrument.shortName,
                                        isin = instrument.isin,
                                        boardId = instrument.boardId,
                                        lotSize = instrument.lotSize,
                                        currency = instrument.currency,
                                        market = MoexMarket.SHARES,
                                        source = instrument.source,
                                        rawFields = emptyMap()
                                    )
                                    val history = sync.syncInstrumentHistory(
                                        instrument = issInstrument,
                                        intervalMinutes = 24,
                                        from = startDate.toString(),
                                        till = endDate.toString()
                                    )
                                    if (!history.isComplete) {
                                        result = BacktestRunResult.Rejected(
                                            "MOEX не подтвердила полноту загрузки истории (${history.completeness}). Тест не выполнен."
                                        )
                                    } else {
                                        when (val verification = verifier.verify(
                                            instrumentId = instrument.instrumentId,
                                            intervalMinutes = 24,
                                            from = startDate.toString(),
                                            till = endDate.toString()
                                        )) {
                                            is HistoryVerificationResult.Verified -> {
                                                val runner = BacktestRunner(BacktestHistoryRepository(dao))
                                                result = runner.runBreakout(
                                                    instrumentId = instrument.instrumentId,
                                                    intervalMinutes = 24,
                                                    from = "${startDate} 00:00:00",
                                                    till = "${endDate} 23:59:59",
                                                    config = BacktestConfig(
                                                        initialCapital = startCapital,
                                                        lotSize = (instrument.lotSize ?: 1.0).toInt().coerceAtLeast(1),
                                                        riskFraction = risk / 100.0
                                                    )
                                                )
                                            }
                                            is HistoryVerificationResult.GapsDetected -> {
                                                result = BacktestRunResult.Rejected(
                                                    "В истории найдены пропуски: ${verification.missingStarts.size}. Тест заблокирован."
                                                )
                                            }
                                            is HistoryVerificationResult.Unknown -> {
                                                result = BacktestRunResult.Rejected(
                                                    "Полноту истории подтвердить не удалось: ${verification.reason}"
                                                )
                                            }
                                        }
                                    }
                                } catch (error: Exception) {
                                    result = BacktestRunResult.Rejected(
                                        "Не удалось выполнить тест: ${error.message ?: error.javaClass.simpleName}"
                                    )
                                } finally {
                                    isRunning = false
                                }
                            }
                        }
                    }
                },
                enabled = !isRunning && selected != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRunning) CircularProgressIndicator() else Text("Запустить тест")
            }
            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (isRunning) Text("Проверяем историю и выполняем расчёт…")
        }
        when (val current = result) {
            is BacktestRunResult.Rejected -> item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Тест не запущен", style = MaterialTheme.typography.titleMedium)
                        Text(current.reason)
                        Text(
                            "Проверьте, что история MOEX загружена полностью и для всего диапазона подтверждено отсутствие пропусков свечей.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            is BacktestRunResult.Completed -> {
                val metrics = current.result.metrics
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Результат тестирования", style = MaterialTheme.typography.titleLarge)
                            Text("Источник: ${current.history.source}")
                            Text("Свечей: ${current.history.candles.size}")
                            Text("Капитал: ${money(metrics.initialCapital)} → ${money(metrics.finalEquity)} ₽")
                            Text("Доходность: ${percent(metrics.totalReturnPercent)}")
                            Text("Сделок: ${metrics.tradeCount} · Win Rate: ${percent(metrics.winRatePercent)}")
                            Text("Средняя прибыль: ${money(metrics.averageWin)} ₽")
                            Text("Средний убыток: ${money(metrics.averageLoss)} ₽")
                            Text("Expectancy: ${money(metrics.expectancy)} ₽")
                            Text("Profit Factor: ${metrics.profitFactor?.let(::number) ?: "не определён"}")
                            Text("Макс. просадка: ${percent(metrics.maxDrawdownPercent)}")
                            Text("Отклонённых входов: ${current.result.rejectedEntries}")
                            Text(
                                "Покрытие истории: ${current.history.freshnessStatus}; получено ${current.history.fetchedAtEpochMillis}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                item { Text("Сделки", style = MaterialTheme.typography.titleLarge) }
                items(current.result.trades, key = { "${it.entryTimestamp}-${it.exitTimestamp}" }) { trade ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${trade.entryTimestamp} → ${trade.exitTimestamp}")
                            Text("Вход ${number(trade.entryPrice)} · выход ${number(trade.exitPrice)} · кол-во ${trade.quantity}")
                            Text("Результат: ${money(trade.netPnl)} ₽ · ${trade.exitReason}")
                        }
                    }
                }
            }
            null -> Unit
        }
    }
}

private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun number(value: Double): String = String.format(Locale.US, "%.4f", value)
private fun percent(value: Double): String = String.format(Locale.US, "%.2f%%", value)
