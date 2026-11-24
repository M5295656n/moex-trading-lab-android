package ru.moex.tradinglab.ui

import android.graphics.Paint as NativePaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ru.moex.tradinglab.data.local.CandleEntity
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class CandleChartWindow(
    val startIndex: Int,
    val endExclusive: Int
) {
    val count: Int get() = endExclusive - startIndex
}

internal fun calculateCandleChartWindow(
    totalCount: Int,
    zoom: Float,
    panFraction: Float
): CandleChartWindow {
    if (totalCount <= 0) return CandleChartWindow(0, 0)

    val minimumVisible = min(totalCount, 10)
    val safeZoom = zoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val visibleCount = ceil(totalCount / safeZoom)
        .toInt()
        .coerceIn(minimumVisible, totalCount)
    val maxStart = totalCount - visibleCount
    val fraction = panFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val start = (fraction * maxStart).roundToInt().coerceIn(0, maxStart)
    return CandleChartWindow(start, start + visibleCount)
}

@Composable
fun CandleChart(
    instrumentId: String,
    intervalMinutes: Int,
    candles: List<CandleEntity>,
    modifier: Modifier = Modifier
) {
    val chartable = remember(candles) { candles.filter { it.hasValidOhlc() } }
    val chartableSourceIndices = remember(candles) {
        candles.mapIndexedNotNull { index, candle -> index.takeIf { candle.hasValidOhlc() } }
    }
    var showSma by remember(instrumentId, intervalMinutes) { mutableStateOf(false) }
    var showEma by remember(instrumentId, intervalMinutes) { mutableStateOf(false) }
    var showBollinger by remember(instrumentId, intervalMinutes) { mutableStateOf(false) }
    var showRsi by remember(instrumentId, intervalMinutes) { mutableStateOf(false) }
    var showMacd by remember(instrumentId, intervalMinutes) { mutableStateOf(false) }
    var showAtr by remember(instrumentId, intervalMinutes) { mutableStateOf(false) }
    val closes = remember(candles) { candles.map { candle -> candle.close.takeIf { candle.hasValidOhlc() } } }
    val highs = remember(candles) { candles.map { candle -> candle.high.takeIf { candle.hasValidOhlc() } } }
    val lows = remember(candles) { candles.map { candle -> candle.low.takeIf { candle.hasValidOhlc() } } }
    val smaValues = remember(closes) { CandleIndicators.sma(closes, 20) }
    val emaValues = remember(closes) { CandleIndicators.ema(closes, 20) }
    val bollinger = remember(closes) { CandleIndicators.bollingerBands(closes, 20, 2.0) }
    val rsiValues = remember(closes) { CandleIndicators.rsi(closes, 14) }
    val macdValues = remember(closes) { CandleIndicators.macd(closes) }
    val atrValues = remember(highs, lows, closes) { CandleIndicators.atr(highs, lows, closes, 14) }
    val initialZoom = (chartable.size / 120f).coerceAtLeast(1f)
    var zoom by remember(instrumentId, intervalMinutes, chartable.size) { mutableFloatStateOf(initialZoom) }
    var panFraction by remember(instrumentId, intervalMinutes, chartable.size) { mutableFloatStateOf(1f) }
    val currentZoom by rememberUpdatedState(zoom)
    val currentPanFraction by rememberUpdatedState(panFraction)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { showSma = !showSma }) { Text(if (showSma) "SMA 20 ✓" else "SMA 20") }
            OutlinedButton(onClick = { showEma = !showEma }) { Text(if (showEma) "EMA 20 ✓" else "EMA 20") }
            OutlinedButton(onClick = { showBollinger = !showBollinger }) { Text(if (showBollinger) "BB ✓" else "BB") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { showRsi = !showRsi }) { Text(if (showRsi) "RSI ✓" else "RSI") }
            OutlinedButton(onClick = { showMacd = !showMacd }) { Text(if (showMacd) "MACD ✓" else "MACD") }
            OutlinedButton(onClick = { showAtr = !showAtr }) { Text(if (showAtr) "ATR ✓" else "ATR") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Свечей: ${chartable.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    zoom = initialZoom
                    panFraction = 1f
                }
            ) {
                Text("Сбросить масштаб")
            }
        }

        if (chartable.isEmpty()) {
            Text(
                text = if (candles.isEmpty()) {
                    "Для этого инструмента и таймфрейма свечи ещё не загружены."
                } else {
                    "Нет корректных OHLC-значений для построения графика. Исходные данные не изменены."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val window = calculateCandleChartWindow(chartable.size, zoom, panFraction)
            val visible = chartable.subList(window.startIndex, window.endExclusive)
            val visibleSourceIndices = chartableSourceIndices.subList(window.startIndex, window.endExclusive)
            val last = visible.last()
            Text(
                text = "Последняя свеча: ${last.begin} · закрытие ${formatChartPrice(last.close!!)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (chartable.size != candles.size) {
                Text(
                    text = "Не отображено некорректных свечей: ${candles.size - chartable.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .pointerInput(instrumentId, intervalMinutes, chartable.size) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val minimumVisible = min(chartable.size, 10)
                            val maximumZoom = (chartable.size / minimumVisible.toFloat()).coerceAtLeast(1f)
                            val nextZoom = (currentZoom * zoomChange).coerceIn(1f, maximumZoom)
                            val visibleCount = calculateCandleChartWindow(
                                chartable.size,
                                nextZoom,
                                currentPanFraction
                            ).count
                            val fractionDelta = if (size.width > 0 && chartable.isNotEmpty()) {
                                pan.x / size.width.toFloat() * visibleCount / chartable.size.toFloat()
                            } else {
                                0f
                            }
                            zoom = nextZoom
                            panFraction = (currentPanFraction - fractionDelta).coerceIn(0f, 1f)
                        }
                    }
            ) {
                val left = 62.dp.toPx()
                val right = size.width - 10.dp.toPx()
                val top = 12.dp.toPx()
                val bottom = size.height - 36.dp.toPx()
                val volumeTop = bottom + 10.dp.toPx()
                val volumeBottom = size.height - 18.dp.toPx()
                val plotWidth = (right - left).coerceAtLeast(1f)
                val plotHeight = (bottom - top).coerceAtLeast(1f)

                val visibleSmaSeries = if (showSma) visibleSourceIndices.map { smaValues[it] } else emptyList()
                val visibleEmaSeries = if (showEma) visibleSourceIndices.map { emaValues[it] } else emptyList()
                val visibleBbUpper = if (showBollinger) visibleSourceIndices.map { bollinger.upper[it] } else emptyList()
                val visibleBbMiddle = if (showBollinger) visibleSourceIndices.map { bollinger.middle[it] } else emptyList()
                val visibleBbLower = if (showBollinger) visibleSourceIndices.map { bollinger.lower[it] } else emptyList()
                val visibleOverlayValues = visibleSmaSeries + visibleEmaSeries + visibleBbUpper + visibleBbMiddle + visibleBbLower
                val overlayFinite = visibleOverlayValues.filterNotNull().filter { it.isFinite() }
                val minPrice = min(visible.minOf { it.low!! }, overlayFinite.minOrNull() ?: Double.POSITIVE_INFINITY)
                val maxPrice = max(visible.maxOf { it.high!! }, overlayFinite.maxOrNull() ?: Double.NEGATIVE_INFINITY)
                val priceRange = (maxPrice - minPrice).takeIf { it > 0.0 }
                    ?: max(kotlin.math.abs(maxPrice) * 0.002, 0.01)
                val gridColor = Color(0xFF52606B)
                val axisColor = Color(0xFFB0BEC5)
                val bullish = Color(0xFF26A69A)
                val bearish = Color(0xFFEF5350)

                val textPaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                    color = axisColor.toArgb()
                    textSize = 10.dp.toPx()
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.NORMAL
                    )
                }

                for (line in 0..4) {
                    val fraction = line / 4f
                    val y = top + plotHeight * fraction
                    drawLine(
                        color = gridColor.copy(alpha = 0.48f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    val price = maxPrice - priceRange * fraction
                    drawContext.canvas.nativeCanvas.drawText(
                        formatChartPrice(price),
                        2.dp.toPx(),
                        y + textPaint.textSize * 0.35f,
                        textPaint
                    )
                }

                val slotWidth = plotWidth / visible.size.toFloat()
                val bodyWidth = (slotWidth * 0.58f).coerceIn(2.dp.toPx(), 14.dp.toPx())
                val maxVolume = visible.mapNotNull { it.volume?.takeIf { value -> value > 0.0 && value.isFinite() } }
                    .maxOrNull()

                visible.forEachIndexed { index, candle ->
                    val open = candle.open!!
                    val high = candle.high!!
                    val low = candle.low!!
                    val close = candle.close!!
                    val centerX = left + slotWidth * (index + 0.5f)
                    val color = when {
                        close > open -> bullish
                        close < open -> bearish
                        else -> axisColor
                    }
                    fun priceY(price: Double): Float =
                        bottom - ((price - minPrice) / priceRange).toFloat() * plotHeight

                    drawLine(
                        color = color,
                        start = Offset(centerX, priceY(high)),
                        end = Offset(centerX, priceY(low)),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    val bodyTop = min(priceY(open), priceY(close))
                    val bodyBottom = max(priceY(open), priceY(close))
                    drawRect(
                        color = color,
                        topLeft = Offset(centerX - bodyWidth / 2f, bodyTop),
                        size = Size(
                            bodyWidth,
                            (bodyBottom - bodyTop).coerceAtLeast(1.dp.toPx())
                        )
                    )

                    val volume = candle.volume?.takeIf { it > 0.0 && it.isFinite() }
                    if (volume != null && maxVolume != null && maxVolume > 0.0) {
                        val barHeight = ((volume / maxVolume) * (volumeBottom - volumeTop)).toFloat()
                        drawRect(
                            color = color.copy(alpha = 0.55f),
                            topLeft = Offset(centerX - bodyWidth / 2f, volumeBottom - barHeight),
                            size = Size(bodyWidth, barHeight.coerceAtLeast(1.dp.toPx()))
                        )
                    }
                }

                fun drawIndicator(values: List<Double?>, color: Color) {
                    val path = Path()
                    var started = false
                    values.forEachIndexed { index, value ->
                        if (value == null || !value.isFinite()) {
                            started = false
                        } else {
                            val x = left + slotWidth * (index + 0.5f)
                            val y = bottom - ((value - minPrice) / priceRange).toFloat() * plotHeight
                            if (started) path.lineTo(x, y) else path.moveTo(x, y)
                            started = true
                        }
                    }
                    drawPath(
                        path,
                        color = color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                }
                if (showSma) drawIndicator(visibleSmaSeries, Color(0xFFFFB74D))
                if (showEma) drawIndicator(visibleEmaSeries, Color(0xFF64B5F6))
                if (showBollinger) {
                    drawIndicator(visibleBbUpper, Color(0xFFAB87D5))
                    drawIndicator(visibleBbMiddle, Color(0xFFCEB6EA))
                    drawIndicator(visibleBbLower, Color(0xFFAB87D5))
                }

                drawLine(
                    color = gridColor,
                    start = Offset(left, volumeTop),
                    end = Offset(right, volumeTop),
                    strokeWidth = 1.dp.toPx()
                )
                textPaint.textSize = 9.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(
                    "Объём",
                    2.dp.toPx(),
                    volumeTop + textPaint.textSize,
                    textPaint
                )

                val labelIndices = listOf(0, visible.size / 2, visible.lastIndex).distinct()
                labelIndices.forEach { index ->
                    val x = left + slotWidth * (index + 0.5f)
                    val raw = visible[index].begin
                    val label = if (intervalMinutes == 24) {
                        raw.take(10)
                    } else {
                        raw.substringAfter(' ', raw).take(5)
                    }
                    val labelWidth = textPaint.measureText(label)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        (x - labelWidth / 2f).coerceIn(left, right - labelWidth),
                        size.height - 2.dp.toPx(),
                        textPaint
                    )
                }
            }
            val visibleRsi = visibleSourceIndices.map { rsiValues[it] }
            val visibleMacdLine = visibleSourceIndices.map { macdValues.line[it] }
            val visibleMacdSignal = visibleSourceIndices.map { macdValues.signal[it] }
            val visibleMacdHistogram = visibleSourceIndices.map { macdValues.histogram[it] }
            val visibleAtr = visibleSourceIndices.map { atrValues[it] }

            if (showRsi) {
                IndicatorPanel(
                    title = "RSI 14",
                    values = listOf(visibleRsi),
                    colors = listOf(Color(0xFF42A5F5)),
                    visibleCount = visible.size,
                    fixedRange = 0.0 to 100.0,
                    guideValues = listOf(30.0, 70.0)
                )
            }
            if (showMacd) {
                IndicatorPanel(
                    title = "MACD 12 / 26 / 9",
                    values = listOf(visibleMacdHistogram, visibleMacdLine, visibleMacdSignal),
                    colors = listOf(Color(0xFF78909C), Color(0xFF26A69A), Color(0xFFFFB74D)),
                    visibleCount = visible.size,
                    zeroLine = true,
                    histogramSeries = 0
                )
            }
            if (showAtr) {
                IndicatorPanel(
                    title = "ATR 14",
                    values = listOf(visibleAtr),
                    colors = listOf(Color(0xFFAB87D5)),
                    visibleCount = visible.size,
                    includeZero = true
                )
            }

            Text(
                text = "Масштабирование двумя пальцами · перемещение графика жестом. Данные отображаются из локальной базы Room.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun IndicatorPanel(
    title: String,
    values: List<List<Double?>>,
    colors: List<Color>,
    visibleCount: Int,
    fixedRange: Pair<Double, Double>? = null,
    guideValues: List<Double> = emptyList(),
    zeroLine: Boolean = false,
    includeZero: Boolean = false,
    histogramSeries: Int? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
        ) {
            val left = 62.dp.toPx()
            val right = size.width - 10.dp.toPx()
            val top = 6.dp.toPx()
            val bottom = size.height - 6.dp.toPx()
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            val finite = values.flatten().filterNotNull().filter { it.isFinite() }
            if (finite.isEmpty() || visibleCount <= 0) {
                val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                    color = Color.Gray.toArgb()
                    textSize = 10.dp.toPx()
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Недостаточно истории для расчёта",
                    left,
                    top + 18.dp.toPx(),
                    paint
                )
                return@Canvas
            }

            var minValue = fixedRange?.first ?: finite.minOrNull()!!
            var maxValue = fixedRange?.second ?: finite.maxOrNull()!!
            if (includeZero) minValue = min(minValue, 0.0)
            if (includeZero) maxValue = max(maxValue, 0.0)
            if (zeroLine) {
                minValue = min(minValue, 0.0)
                maxValue = max(maxValue, 0.0)
            }
            if (maxValue <= minValue) {
                val padding = max(kotlin.math.abs(maxValue) * 0.05, 0.01)
                minValue -= padding
                maxValue += padding
            }
            val range = maxValue - minValue
            val grid = Color(0xFF52606B)
            val axis = Color(0xFFB0BEC5)
            val textPaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                color = axis.toArgb()
                textSize = 9.dp.toPx()
            }
            val yFor = { value: Double ->
                bottom - ((value - minValue) / range).toFloat() * height
            }
            val guides = (guideValues + if (zeroLine) listOf(0.0) else emptyList()).distinct()
            guides.forEach { value ->
                if (value in minValue..maxValue) {
                    val y = yFor(value)
                    drawLine(
                        color = grid.copy(alpha = 0.8f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        DecimalFormat("0.##").format(value),
                        2.dp.toPx(),
                        y + textPaint.textSize * 0.35f,
                        textPaint
                    )
                }
            }

            val slot = width / visibleCount.toFloat()
            values.forEachIndexed { seriesIndex, series ->
                val color = colors.getOrElse(seriesIndex) { axis }
                if (seriesIndex == histogramSeries) {
                    series.forEachIndexed { index, raw ->
                        val value = raw?.takeIf { it.isFinite() } ?: return@forEachIndexed
                        val x = left + slot * (index + 0.5f)
                        val zeroY = yFor(0.0.coerceIn(minValue, maxValue))
                        val valueY = yFor(value.coerceIn(minValue, maxValue))
                        drawRect(
                            color = color.copy(alpha = 0.8f),
                            topLeft = Offset(x - max(slot * 0.28f, 1.dp.toPx()), min(zeroY, valueY)),
                            size = Size(
                                max(slot * 0.56f, 1.dp.toPx()),
                                max(kotlin.math.abs(valueY - zeroY), 1.dp.toPx())
                            )
                        )
                    }
                } else {
                    val path = Path()
                    var started = false
                    series.forEachIndexed { index, raw ->
                        val value = raw?.takeIf { it.isFinite() }
                        if (value == null) {
                            started = false
                        } else {
                            val x = left + slot * (index + 0.5f)
                            val y = yFor(value.coerceIn(minValue, maxValue))
                            if (started) path.lineTo(x, y) else path.moveTo(x, y)
                            started = true
                        }
                    }
                    drawPath(
                        path,
                        color = color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx())
                    )
                }
            }
        }
    }
}

private fun CandleEntity.hasValidOhlc(): Boolean {
    val open = open
    val high = high
    val low = low
    val close = close
    return open != null && high != null && low != null && close != null &&
        open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() &&
        high >= max(open, close) && low <= min(open, close)
}

private fun formatChartPrice(value: Double): String {
    val symbols = DecimalFormatSymbols(Locale("ru", "RU")).apply {
        groupingSeparator = ' '
        decimalSeparator = ','
    }
    return DecimalFormat("#,##0.00", symbols).format(value)
}
