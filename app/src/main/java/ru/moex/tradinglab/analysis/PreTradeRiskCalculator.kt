package ru.moex.tradinglab.analysis

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import java.time.LocalDate

enum class RiskInstrumentType { EQUITY, FUTURE }
enum class TradeDirection { LONG, SHORT }

data class FuturesRiskSpec(
    val tickSize: Double,
    val tickValue: Double,
    val initialMarginPerContract: Double,
    val expirationDate: String,
    val source: String,
    val fetchedAtEpochMillis: Long
) {
    init {
        require(tickSize.isFinite() && tickSize > 0.0)
        require(tickValue.isFinite() && tickValue > 0.0)
        require(initialMarginPerContract.isFinite() && initialMarginPerContract > 0.0)
        require(runCatching { LocalDate.parse(expirationDate) }.isSuccess)
        require(source.isNotBlank())
        require(fetchedAtEpochMillis > 0L)
    }

    val contractMultiplier: Double get() = tickValue / tickSize
}

data class PreTradeRiskInput(
    val capital: Double = 1_000.0,
    val riskFraction: Double = 0.02,
    val instrumentType: RiskInstrumentType,
    val direction: TradeDirection,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val lotSize: Int = 1,
    val commissionRatePerSide: Double = 0.0005,
    val slippageBasisPointsPerSide: Double = 5.0,
    val futuresSpec: FuturesRiskSpec? = null,
    val commissionPerContractPerSide: Double? = null,
    val asOfDate: String
)

sealed interface PreTradeRiskResult {
    data class Ready(
        val quantity: Int,
        val riskBudget: Double,
        val estimatedMaximumLoss: Double,
        val estimatedNetReward: Double,
        val rewardRiskRatio: Double,
        val entryNotional: Double,
        val requiredCapital: Double,
        val estimatedRoundTripCommission: Double,
        val estimatedRoundTripSlippage: Double,
        val instrumentType: RiskInstrumentType
    ) : PreTradeRiskResult

    data class Rejected(val reason: String) : PreTradeRiskResult
}

/** Conservative sizing estimate only. It never creates or submits a broker order. */
object PreTradeRiskCalculator {
    fun calculate(input: PreTradeRiskInput): PreTradeRiskResult {
        if (!input.capital.isFinite() || input.capital <= 0.0) return rejected("Капитал должен быть больше нуля")
        if (!input.riskFraction.isFinite() || input.riskFraction <= 0.0 || input.riskFraction > 1.0) {
            return rejected("Доля риска должна быть в диапазоне (0; 1]")
        }
        if (!input.entryPrice.isFinite() || input.entryPrice <= 0.0 ||
            !input.stopPrice.isFinite() || input.stopPrice <= 0.0 ||
            !input.targetPrice.isFinite() || input.targetPrice <= 0.0
        ) return rejected("Цена входа, стопа и цели должны быть положительными")
        if (!input.commissionRatePerSide.isFinite() || input.commissionRatePerSide < 0.0 ||
            !input.slippageBasisPointsPerSide.isFinite() || input.slippageBasisPointsPerSide < 0.0
        ) return rejected("Комиссия и проскальзывание заданы некорректно")
        val asOfDate = runCatching { LocalDate.parse(input.asOfDate) }.getOrNull()
            ?: return rejected("Дата расчёта указана некорректно")
        if (input.commissionPerContractPerSide != null &&
            (!input.commissionPerContractPerSide.isFinite() || input.commissionPerContractPerSide < 0.0)
        ) return rejected("Комиссия за контракт задана некорректно")

        if (input.instrumentType == RiskInstrumentType.EQUITY && input.direction == TradeDirection.SHORT) {
            return rejected("Короткая позиция по акциям требует отдельной проверки займа и маржинальных требований")
        }

        val isLong = input.direction == TradeDirection.LONG
        val stopIsValid = if (isLong) input.stopPrice < input.entryPrice else input.stopPrice > input.entryPrice
        val targetIsValid = if (isLong) input.targetPrice > input.entryPrice else input.targetPrice < input.entryPrice
        if (!stopIsValid || !targetIsValid) return rejected("Стоп и цель должны находиться по правильную сторону от входа")

        val spec = if (input.instrumentType == RiskInstrumentType.FUTURE) {
            input.futuresSpec ?: return rejected("Нет спецификации фьючерса: расчёт заблокирован")
        } else null

        if (spec != null) {
            if (input.commissionPerContractPerSide == null) return rejected("Не задана комиссия брокера за контракт")
            if (LocalDate.parse(spec.expirationDate).isBefore(asOfDate)) return rejected("Срок экспирации контракта уже прошёл")
            if (!isAligned(input.entryPrice, spec.tickSize) ||
                !isAligned(input.stopPrice, spec.tickSize) ||
                !isAligned(input.targetPrice, spec.tickSize)
            ) return rejected("Цены входа, стопа и цели должны соответствовать шагу цены фьючерса")
        } else if (input.lotSize <= 0) {
            return rejected("Размер биржевого лота должен быть положительным")
        }

        val multiplier = spec?.contractMultiplier ?: 1.0
        if (!multiplier.isFinite() || multiplier <= 0.0) return rejected("Не удалось определить стоимость пункта контракта")

        val slip = input.slippageBasisPointsPerSide / 10_000.0
        val executedEntry = if (isLong) input.entryPrice * (1.0 + slip) else input.entryPrice * (1.0 - slip)
        val executedStop = if (isLong) input.stopPrice * (1.0 - slip) else input.stopPrice * (1.0 + slip)
        val executedTarget = if (isLong) input.targetPrice * (1.0 - slip) else input.targetPrice * (1.0 + slip)

        val entryNotionalPerUnit = executedEntry * multiplier
        val stopNotionalPerUnit = executedStop * multiplier
        val targetNotionalPerUnit = executedTarget * multiplier
        val entryFeePerUnit = spec?.let { input.commissionPerContractPerSide!! }
            ?: (entryNotionalPerUnit * input.commissionRatePerSide)
        val stopFeePerUnit = spec?.let { input.commissionPerContractPerSide!! }
            ?: (stopNotionalPerUnit * input.commissionRatePerSide)
        val targetFeePerUnit = spec?.let { input.commissionPerContractPerSide!! }
            ?: (targetNotionalPerUnit * input.commissionRatePerSide)

        val lossPerUnit = kotlin.math.abs(executedEntry - executedStop) * multiplier + entryFeePerUnit + stopFeePerUnit
        val rewardPerUnit = kotlin.math.abs(executedTarget - executedEntry) * multiplier - entryFeePerUnit - targetFeePerUnit
        if (!lossPerUnit.isFinite() || lossPerUnit <= 0.0 || !rewardPerUnit.isFinite() || rewardPerUnit <= 0.0) {
            return rejected("После учёта издержек риск или потенциальная прибыль некорректны")
        }

        val riskBudget = input.capital * input.riskFraction
        val riskLimitedUnits = floor(riskBudget / lossPerUnit).toInt().coerceAtLeast(0)
        val capitalPerUnit = if (spec == null) {
            entryNotionalPerUnit + entryFeePerUnit
        } else {
            spec.initialMarginPerContract + entryFeePerUnit
        }
        val capitalLimitedUnits = floor(input.capital / capitalPerUnit).toInt().coerceAtLeast(0)
        val rawUnits = min(riskLimitedUnits, capitalLimitedUnits)
        val quantityStep = if (spec == null) input.lotSize else 1
        val quantity = (rawUnits / quantityStep) * quantityStep
        if (quantity < quantityStep) {
            return rejected(
                if (riskLimitedUnits < quantityStep) "Минимальный лот превышает допустимый денежный риск"
                else "Недостаточно капитала для минимального лота или гарантийного обеспечения"
            )
        }

        val totalLoss = lossPerUnit * quantity
        val totalReward = rewardPerUnit * quantity
        val totalEntryNotional = entryNotionalPerUnit * quantity
        val roundTripCommission = (entryFeePerUnit + stopFeePerUnit) * quantity
        val roundTripSlippage = (
            abs(executedEntry - input.entryPrice) + abs(executedStop - input.stopPrice)
        ) * multiplier * quantity
        val requiredCapital = capitalPerUnit * quantity
        val ratio = totalReward / totalLoss

        if (totalLoss > riskBudget + 1e-8 || !ratio.isFinite()) {
            return rejected("Расчёт не прошёл контроль лимита риска")
        }
        return PreTradeRiskResult.Ready(
            quantity = quantity,
            riskBudget = riskBudget,
            estimatedMaximumLoss = totalLoss,
            estimatedNetReward = totalReward,
            rewardRiskRatio = ratio,
            entryNotional = totalEntryNotional,
            requiredCapital = requiredCapital,
            estimatedRoundTripCommission = roundTripCommission,
            estimatedRoundTripSlippage = roundTripSlippage,
            instrumentType = input.instrumentType
        )
    }

    private fun isAligned(price: Double, tickSize: Double): Boolean {
        val ticks = price / tickSize
        return abs(ticks - round(ticks)) <= 1e-7
    }

    private fun rejected(reason: String) = PreTradeRiskResult.Rejected(reason)
}
