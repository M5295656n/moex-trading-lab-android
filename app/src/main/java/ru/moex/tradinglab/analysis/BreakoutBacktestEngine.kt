package ru.moex.tradinglab.analysis

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class BacktestCandle(
    val timestamp: String,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val volume: Double?
)

data class BacktestConfig(
    val initialCapital: Double = 1_000.0,
    val lotSize: Int = 1,
    val riskFraction: Double = 0.01,
    val atrPeriod: Int = 14,
    val atrStopMultiple: Double = 2.0,
    val rewardRiskRatio: Double = 2.0,
    val commissionRatePerSide: Double = 0.0005,
    val slippageBasisPointsPerSide: Double = 5.0,
    val breakoutLookbackBars: Int = 20,
    val volumeBaselineBars: Int = 20,
    val minimumRelativeVolume: Double = 1.5
) {
    init {
        require(initialCapital.isFinite() && initialCapital > 0.0)
        require(lotSize > 0)
        require(riskFraction.isFinite() && riskFraction > 0.0 && riskFraction <= 1.0)
        require(atrPeriod > 0)
        require(atrStopMultiple.isFinite() && atrStopMultiple > 0.0)
        require(rewardRiskRatio.isFinite() && rewardRiskRatio > 0.0)
        require(commissionRatePerSide.isFinite() && commissionRatePerSide >= 0.0)
        require(slippageBasisPointsPerSide.isFinite() && slippageBasisPointsPerSide >= 0.0)
        require(breakoutLookbackBars > 0 && volumeBaselineBars > 0)
        require(minimumRelativeVolume.isFinite() && minimumRelativeVolume > 0.0)
    }
}

enum class BacktestExitReason { STOP_LOSS, TAKE_PROFIT, END_OF_DATA }

data class BacktestTrade(
    val entryTimestamp: String,
    val exitTimestamp: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Int,
    val grossPnl: Double,
    val commissions: Double,
    val netPnl: Double,
    val exitReason: BacktestExitReason
)

data class BacktestMetrics(
    val initialCapital: Double,
    val finalEquity: Double,
    val totalReturnPercent: Double,
    val tradeCount: Int,
    val winRatePercent: Double,
    val averageWin: Double,
    val averageLoss: Double,
    val expectancy: Double,
    val profitFactor: Double?,
    val maxDrawdownPercent: Double
)

data class BacktestResult(
    val trades: List<BacktestTrade>,
    val metrics: BacktestMetrics,
    val rejectedEntries: Int
)

/**
 * Long-only, single-position historical simulator for BreakoutVolumeStrategy.
 *
 * Signals are evaluated on a completed candle close and can only enter at the
 * next candle open. Stops/targets are ATR-based. If both are touched within
 * one candle, the stop is assumed first (conservative OHLC ambiguity handling).
 * No broker orders are submitted.
 */
object BreakoutBacktestEngine {
    fun run(candles: List<BacktestCandle>, config: BacktestConfig = BacktestConfig()): BacktestResult {
        validateChronology(candles)
        val initialCapital = config.initialCapital
        var cash = initialCapital
        var peakEquity = initialCapital
        var maxDrawdown = 0.0
        var position: Position? = null
        var pending: PendingEntry? = null
        var rejectedEntries = 0
        val trades = mutableListOf<BacktestTrade>()

        val atrValues = calculateAtr(candles, config.atrPeriod)

        candles.forEachIndexed { index, candle ->
            val open = candle.open!!
            val high = candle.high!!
            val low = candle.low!!
            val close = candle.close!!

            // A signal from the previous completed bar enters no earlier than this open.
            val scheduled = pending
            pending = null
            if (position == null && scheduled != null) {
                val entryPrice = applySlippage(open, isBuy = true, config)
                val stopDistance = scheduled.atr * config.atrStopMultiple
                val stopPrice = entryPrice - stopDistance
                val targetPrice = entryPrice + stopDistance * config.rewardRiskRatio
                val quantity = calculateQuantity(
                    cash = cash,
                    entryPrice = entryPrice,
                    stopDistance = stopDistance,
                    config = config
                )
                if (quantity >= config.lotSize && stopPrice > 0.0) {
                    val entryCommission = entryPrice * quantity * config.commissionRatePerSide
                    cash -= entryPrice * quantity + entryCommission
                    position = Position(
                        entryTimestamp = candle.timestamp,
                        entryPrice = entryPrice,
                        quantity = quantity,
                        stopPrice = stopPrice,
                        targetPrice = targetPrice,
                        entryCommission = entryCommission
                    )
                } else {
                    rejectedEntries++
                }
            }

            val active = position
            if (active != null) {
                val stopTouched = low <= active.stopPrice
                val targetTouched = high >= active.targetPrice
                if (stopTouched || targetTouched) {
                    // Pessimistic rule: if both levels occur in one OHLC bar, stop wins.
                    val reason: BacktestExitReason
                    val rawExit: Double
                    if (stopTouched) {
                        reason = BacktestExitReason.STOP_LOSS
                        rawExit = if (open <= active.stopPrice) open else active.stopPrice
                    } else {
                        reason = BacktestExitReason.TAKE_PROFIT
                        rawExit = if (open >= active.targetPrice) open else active.targetPrice
                    }
                    val trade = closePosition(active, candle.timestamp, rawExit, reason, cash, config)
                    cash = trade.first
                    trades += trade.second
                    position = null
                }
            }

            // Force-close an open position at the last available close.
            if (index == candles.lastIndex && position != null) {
                val trade = closePosition(
                    position!!,
                    candle.timestamp,
                    close,
                    BacktestExitReason.END_OF_DATA,
                    cash,
                    config
                )
                cash = trade.first
                trades += trade.second
                position = null
            }

            val markedEquity = if (position == null) cash else cash + close * position!!.quantity
            if (markedEquity > peakEquity) peakEquity = markedEquity
            if (peakEquity > 0.0) {
                maxDrawdown = max(maxDrawdown, (peakEquity - markedEquity) / peakEquity * 100.0)
            }

            // The current candle's close and volume can create a signal only for the next bar.
            if (position == null && pending == null && index < candles.lastIndex) {
                val signalCandles = candles.take(index + 1).map {
                    AnalysisCandle(it.high, it.low, it.close, it.volume)
                }
                val signal = BreakoutVolumeStrategy.evaluate(
                    candles = signalCandles,
                    lookbackBars = config.breakoutLookbackBars,
                    volumeBaselineBars = config.volumeBaselineBars,
                    minimumRelativeVolume = config.minimumRelativeVolume
                )
                val atr = atrValues[index]
                if (signal.status == BreakoutSignalStatus.CONFIRMED && atr != null && atr.isFinite() && atr > 0.0) {
                    pending = PendingEntry(atr)
                }
            }
        }

        val metrics = calculateMetrics(initialCapital, cash, trades, maxDrawdown)
        return BacktestResult(trades.toList(), metrics, rejectedEntries)
    }

    private fun validateChronology(candles: List<BacktestCandle>) {
        require(candles.size >= 2) { "At least two candles are required" }
        var previousTimestamp: String? = null
        candles.forEachIndexed { index, candle ->
            require(candle.timestamp.isNotBlank()) { "Candle $index has no timestamp" }
            require(previousTimestamp == null || candle.timestamp > previousTimestamp!!) {
                "Candles must have strictly increasing timestamps"
            }
            previousTimestamp = candle.timestamp
            val open = candle.open
            val high = candle.high
            val low = candle.low
            val close = candle.close
            require(open != null && high != null && low != null && close != null) {
                "Candle $index has missing OHLC values"
            }
            require(listOf(open, high, low, close).all { it.isFinite() && it > 0.0 }) {
                "Candle $index has invalid OHLC values"
            }
            require(high >= max(open, close) && low <= min(open, close) && high >= low) {
                "Candle $index has inconsistent OHLC values"
            }
            require(candle.volume == null || (candle.volume.isFinite() && candle.volume >= 0.0)) {
                "Candle $index has invalid volume"
            }
        }
    }

    internal fun calculateAtr(candles: List<BacktestCandle>, period: Int): List<Double?> {
        val result = MutableList<Double?>(candles.size) { null }
        var previousClose: Double? = null
        var sum = 0.0
        var count = 0
        var previousAtr: Double? = null
        candles.forEachIndexed { index, candle ->
            val high = candle.high!!
            val low = candle.low!!
            val close = candle.close!!
            val priorClose = previousClose
            val tr = if (priorClose == null) high - low else max(
                high - low,
                max(kotlin.math.abs(high - priorClose), kotlin.math.abs(low - priorClose))
            )
            previousClose = close
            val priorAtr = previousAtr
            if (priorAtr == null) {
                sum += tr
                count++
                if (count == period) {
                    val seed = sum / period
                    if (seed.isFinite() && seed >= 0.0) {
                        result[index] = seed
                        previousAtr = seed
                    }
                }
            } else {
                val next = (priorAtr * (period - 1) + tr) / period
                if (next.isFinite() && next >= 0.0) {
                    result[index] = next
                    previousAtr = next
                }
            }
        }
        return result
    }

    private fun calculateQuantity(
        cash: Double,
        entryPrice: Double,
        stopDistance: Double,
        config: BacktestConfig
    ): Int {
        val slippage = config.slippageBasisPointsPerSide / 10_000.0
        val estimatedExitPrice = max(entryPrice - stopDistance, 0.0)
        val estimatedCostPerUnit = stopDistance +
            entryPrice * config.commissionRatePerSide +
            estimatedExitPrice * config.commissionRatePerSide +
            entryPrice * slippage +
            estimatedExitPrice * slippage
        if (!estimatedCostPerUnit.isFinite() || estimatedCostPerUnit <= 0.0) return 0
        val riskBudget = cash * config.riskFraction
        val riskLimited = floor(riskBudget / estimatedCostPerUnit).toInt().coerceAtLeast(0)
        val affordabilityLimited = floor(
            cash / (entryPrice * (1.0 + slippage) * (1.0 + config.commissionRatePerSide))
        ).toInt().coerceAtLeast(0)
        val raw = min(riskLimited, affordabilityLimited)
        return (raw / config.lotSize) * config.lotSize
    }

    private fun closePosition(
        position: Position,
        timestamp: String,
        rawExit: Double,
        reason: BacktestExitReason,
        cashBeforeExit: Double,
        config: BacktestConfig
    ): Pair<Double, BacktestTrade> {
        val exitPrice = applySlippage(rawExit, isBuy = false, config)
        val exitCommission = exitPrice * position.quantity * config.commissionRatePerSide
        val gross = (exitPrice - position.entryPrice) * position.quantity
        val commissions = position.entryCommission + exitCommission
        val net = gross - commissions
        val cashAfterExit = cashBeforeExit + exitPrice * position.quantity - exitCommission
        return cashAfterExit to BacktestTrade(
            entryTimestamp = position.entryTimestamp,
            exitTimestamp = timestamp,
            entryPrice = position.entryPrice,
            exitPrice = exitPrice,
            quantity = position.quantity,
            grossPnl = gross,
            commissions = commissions,
            netPnl = net,
            exitReason = reason
        )
    }

    private fun applySlippage(price: Double, isBuy: Boolean, config: BacktestConfig): Double {
        val fraction = config.slippageBasisPointsPerSide / 10_000.0
        return if (isBuy) price * (1.0 + fraction) else price * (1.0 - fraction)
    }

    internal fun calculateMetrics(
        initialCapital: Double,
        finalEquity: Double,
        trades: List<BacktestTrade>,
        maxDrawdown: Double
    ): BacktestMetrics {
        val wins = trades.filter { it.netPnl > 0.0 }
        val losses = trades.filter { it.netPnl < 0.0 }
        val grossProfit = wins.sumOf { it.netPnl }
        val grossLoss = -losses.sumOf { it.netPnl }
        val total = trades.sumOf { it.netPnl }
        return BacktestMetrics(
            initialCapital = initialCapital,
            finalEquity = finalEquity,
            totalReturnPercent = (finalEquity - initialCapital) / initialCapital * 100.0,
            tradeCount = trades.size,
            winRatePercent = if (trades.isEmpty()) 0.0 else wins.size.toDouble() / trades.size * 100.0,
            averageWin = if (wins.isEmpty()) 0.0 else grossProfit / wins.size,
            averageLoss = if (losses.isEmpty()) 0.0 else losses.sumOf { it.netPnl } / losses.size,
            expectancy = if (trades.isEmpty()) 0.0 else total / trades.size,
            profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
            maxDrawdownPercent = maxDrawdown
        )
    }

    private data class Position(
        val entryTimestamp: String,
        val entryPrice: Double,
        val quantity: Int,
        val stopPrice: Double,
        val targetPrice: Double,
        val entryCommission: Double
    )

    private data class PendingEntry(val atr: Double)
}
