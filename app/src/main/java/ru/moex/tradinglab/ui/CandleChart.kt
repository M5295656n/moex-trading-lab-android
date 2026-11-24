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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    var zoom by remember(instrumentId, intervalMinutes) { mutableFloatStateOf(1f) }
    var panFraction by remember(instrumentId, intervalMinutes) { mutableFloatStateOf(1f) }

    val chartable = remember(candles) {
        candles.filter { candle ->
            val open = candle.open
            val high = candle.high
            val low = candle.low
            val close = candle.close
            open != null && high != null && low != null && close != null &&
                open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() &&
                high >= low
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    zoom = 1f
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
                            val nextZoom = (zoom * zoomChange).coerceIn(1f, maximumZoom)
                            val visibleCount = calculateCandleChartWindow(
                                chartable.size,
                                nextZoom,
                                panFraction
                            ).count
                            val fractionDelta = if (size.width > 0 && chartable.isNotEmpty()) {
                                pan.x / size.width.toFloat() * visibleCount / chartable.size.toFloat()
                            } else {
                                0f
                            }
                            zoom = nextZoom
                            panFraction = (panFraction - fractionDelta).coerceIn(0f, 1f)
                        }
                    }
            ) {
                val density = this.density
                val left = 62.dp.toPx()
                val right = size.width - 10.dp.toPx()
                val top = 12.dp.toPx()
                val bottom = size.height - 36.dp.toPx()
                val volumeTop = bottom + 10.dp.toPx()
                val volumeBottom = size.height - 18.dp.toPx()
                val plotWidth = (right - left).coerceAtLeast(1f)
                val plotHeight = (bottom - top).coerceAtLeast(1f)

                val minPrice = visible.minOf { it.low!! }
                val maxPrice = visible.maxOf { it.high!! }
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
            Text(
                text = "Масштабирование двумя пальцами · перемещение графика жестом. Данные отображаются из локальной базы Room.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatChartPrice(value: Double): String {
    val symbols = DecimalFormatSymbols(Locale("ru", "RU")).apply {
        groupingSeparator = ' '
        decimalSeparator = ','
    }
    return DecimalFormat("#,##0.00", symbols).format(value)
}
