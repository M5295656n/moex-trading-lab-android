package ru.moex.tradinglab.analysis

import ru.moex.tradinglab.data.local.CandleEntity
import ru.moex.tradinglab.data.local.HistoryCoverageEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fail-closed validation for Room history before it is passed to the simulator.
 * Unknown coverage is not treated as complete and absent OHLC is never filled.
 */
object BacktestHistoryGate {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun rejectionReason(
        coverage: HistoryCoverageEntity?,
        candles: List<CandleEntity>,
        instrumentId: String,
        intervalMinutes: Int,
        from: String,
        till: String
    ): String? {
        if (coverage == null) return "Нет записи покрытия истории для выбранного диапазона"
        if (coverage.instrumentId != instrumentId || coverage.intervalMinutes != intervalMinutes) {
            return "Покрытие относится к другому инструменту или таймфрейму"
        }
        if (coverage.source != "MOEX ISS") return "Источник истории не подтверждён как MOEX ISS"
        if (coverage.requestedFrom.isNullOrBlank() || coverage.requestedTill.isNullOrBlank() ||
            coverage.requestedFrom > from || coverage.requestedTill < till
        ) return "Запись покрытия не охватывает весь выбранный диапазон"
        if (coverage.paginationStatus != "COMPLETE") {
            return "Загрузка истории не завершена: ${coverage.paginationStatus}"
        }
        if (coverage.skippedRows != 0) return "При загрузке были пропущены строки: ${coverage.skippedRows}"
        if (coverage.gapCount == null || coverage.gapsJson.isNullOrBlank()) {
            return "Непрерывность истории не проверена; сначала требуется анализ пропусков свечей"
        }
        if (coverage.gapCount != 0 || coverage.gapsJson.trim() != "[]") {
            return "В выбранной истории обнаружены пропуски свечей"
        }
        if (candles.size < 2) return "Недостаточно свечей для тестирования (нужно минимум 2)"
        if (candles.any { it.instrumentId != instrumentId || it.intervalMinutes != intervalMinutes }) {
            return "В выборке присутствуют свечи другого инструмента или таймфрейма"
        }
        if (candles.any { it.source != coverage.source }) {
            return "Источники свечей и записи покрытия не совпадают"
        }

        var previous: LocalDateTime? = null
        candles.forEachIndexed { index, candle ->
            val timestamp = runCatching {
                LocalDateTime.parse(candle.begin, timestampFormatter)
            }.getOrNull() ?: return "Некорректное время свечи №${index + 1}"
            if (previous != null && !timestamp.isAfter(previous)) {
                return "Свечи не расположены в строгом хронологическом порядке"
            }
            previous = timestamp

            val o = candle.open
            val h = candle.high
            val l = candle.low
            val c = candle.close
            if (o == null || h == null || l == null || c == null) {
                return "У свечи №${index + 1} отсутствует OHLC; данные не будут заменены нулями"
            }
            if (listOf(o, h, l, c).any { !it.isFinite() || it <= 0.0 } ||
                h < maxOf(o, c) || l > minOf(o, c) || h < l
            ) return "У свечи №${index + 1} некорректные OHLC"
            if (candle.volume != null && (!candle.volume.isFinite() || candle.volume < 0.0)) {
                return "У свечи №${index + 1} некорректный объём"
            }
        }
        return null
    }
}
