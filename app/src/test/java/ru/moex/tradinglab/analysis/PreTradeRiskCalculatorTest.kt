package ru.moex.tradinglab.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PreTradeRiskCalculatorTest {
    private val testNow = LocalDate.of(2026, 11, 25).atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
    private val freshSpecTime = testNow - 1_000L
    private fun equityInput(
        capital: Double = 1_000.0,
        riskFraction: Double = 0.02,
        entry: Double = 100.0,
        stop: Double = 95.0,
        target: Double = 110.0,
        lotSize: Int = 1
    ) = PreTradeRiskInput(
        capital = capital,
        riskFraction = riskFraction,
        instrumentType = RiskInstrumentType.EQUITY,
        direction = TradeDirection.LONG,
        entryPrice = entry,
        stopPrice = stop,
        targetPrice = target,
        lotSize = lotSize,
        commissionRatePerSide = 0.0005,
        slippageBasisPointsPerSide = 5.0,
        asOfDate = "2026-11-25"
    )

    @Test fun sizesEquityPositionWithinRiskBudgetAndCapital() {
        val result = PreTradeRiskCalculator.calculate(equityInput())
        assertTrue(result is PreTradeRiskResult.Ready)
        val ready = result as PreTradeRiskResult.Ready
        assertTrue(ready.quantity > 0)
        assertTrue(ready.estimatedMaximumLoss <= ready.riskBudget)
        assertTrue(ready.requiredCapital <= 1_000.0)
        assertTrue(ready.rewardRiskRatio > 0.0)
    }

    @Test fun rejectsWhenExchangeLotExceedsRiskBudget() {
        val result = PreTradeRiskCalculator.calculate(equityInput(entry = 250.0, stop = 240.0, target = 270.0, lotSize = 10))
        assertTrue(result is PreTradeRiskResult.Rejected)
        assertTrue((result as PreTradeRiskResult.Rejected).reason.contains("лот"))
    }

    @Test fun rejectsMissingFuturesSpecification() {
        val result = PreTradeRiskCalculator.calculate(equityInput().copy(instrumentType = RiskInstrumentType.FUTURE))
        assertEquals("Нет спецификации фьючерса: расчёт заблокирован", (result as PreTradeRiskResult.Rejected).reason)
    }

    @Test fun futuresSizingRespectsTickValueAndMargin() {
        val spec = FuturesRiskSpec(1.0, 1.0, 120.0, "2026-12-17", "MOEX ISS contract specification", freshSpecTime)
        val input = PreTradeRiskInput(
            capital = 1_000.0, riskFraction = 0.02,
            instrumentType = RiskInstrumentType.FUTURE, direction = TradeDirection.LONG,
            entryPrice = 100.0, stopPrice = 90.0, targetPrice = 120.0,
            commissionRatePerSide = 0.0001, slippageBasisPointsPerSide = 1.0,
            futuresSpec = spec, commissionPerContractPerSide = 1.2, currentTimeEpochMillis = testNow, asOfDate = "2026-11-25"
        )
        val result = PreTradeRiskCalculator.calculate(input)
        assertTrue(result is PreTradeRiskResult.Ready)
        val ready = result as PreTradeRiskResult.Ready
        assertTrue(ready.quantity >= 1)
        assertTrue(ready.requiredCapital <= input.capital)
        assertTrue(ready.estimatedMaximumLoss <= input.capital * input.riskFraction)
    }

    @Test fun rejectsOffTickFuturesPrices() {
        val spec = FuturesRiskSpec(1.0, 1.0, 100.0, "2026-12-17", "MOEX", freshSpecTime)
        val input = PreTradeRiskInput(
            instrumentType = RiskInstrumentType.FUTURE, direction = TradeDirection.LONG,
            entryPrice = 100.5, stopPrice = 90.0, targetPrice = 120.0,
            futuresSpec = spec, commissionPerContractPerSide = 1.0, currentTimeEpochMillis = testNow, asOfDate = "2026-11-25"
        )
        assertTrue((PreTradeRiskCalculator.calculate(input) as PreTradeRiskResult.Rejected).reason.contains("шагу"))
    }

    @Test fun rejectsExpiredFuture() {
        val spec = FuturesRiskSpec(1.0, 1.0, 100.0, "2026-11-20", "MOEX", freshSpecTime)
        val input = PreTradeRiskInput(
            instrumentType = RiskInstrumentType.FUTURE, direction = TradeDirection.LONG,
            entryPrice = 100.0, stopPrice = 90.0, targetPrice = 120.0,
            futuresSpec = spec, commissionPerContractPerSide = 1.0, currentTimeEpochMillis = testNow, asOfDate = "2026-11-25"
        )
        assertTrue((PreTradeRiskCalculator.calculate(input) as PreTradeRiskResult.Rejected).reason.contains("экспирации"))
    }

    @Test fun rejectsEquityShortWithoutBorrowAndMarginModel() {
        val input = equityInput(entry = 100.0, stop = 105.0, target = 90.0).copy(direction = TradeDirection.SHORT)
        val result = PreTradeRiskCalculator.calculate(input)
        assertTrue(result is PreTradeRiskResult.Rejected)
        assertTrue((result as PreTradeRiskResult.Rejected).reason.contains("маржинальных"))
    }


    @Test fun rejectsStaleFuturesSpecification() {
        val staleNow = testNow + 90_000_000L
        val spec = FuturesRiskSpec(1.0, 1.0, 100.0, "2026-12-17", "MOEX", staleNow - 90_000_001L)
        val input = PreTradeRiskInput(
            instrumentType = RiskInstrumentType.FUTURE, direction = TradeDirection.LONG,
            entryPrice = 100.0, stopPrice = 90.0, targetPrice = 120.0,
            futuresSpec = spec, commissionPerContractPerSide = 1.0,
            currentTimeEpochMillis = staleNow, maxSpecificationAgeMillis = 86_400_000L,
            asOfDate = "2026-11-26"
        )
        val result = PreTradeRiskCalculator.calculate(input)
        assertTrue(result is PreTradeRiskResult.Rejected)
        assertTrue((result as PreTradeRiskResult.Rejected).reason.contains("устарела"))
    }

    @Test fun rejectsStaleCallerDateForFuturesExpirationCheck() {
        val spec = FuturesRiskSpec(1.0, 1.0, 100.0, "2026-11-24", "MOEX", freshSpecTime)
        val input = PreTradeRiskInput(
            instrumentType = RiskInstrumentType.FUTURE, direction = TradeDirection.LONG,
            entryPrice = 100.0, stopPrice = 90.0, targetPrice = 120.0,
            futuresSpec = spec, commissionPerContractPerSide = 1.0,
            currentTimeEpochMillis = testNow, asOfDate = "2026-11-20"
        )
        val result = PreTradeRiskCalculator.calculate(input)
        assertTrue(result is PreTradeRiskResult.Rejected)
        assertTrue((result as PreTradeRiskResult.Rejected).reason.contains("текущей датой"))
    }

    @Test fun rejectsStopAndTargetOnWrongSide() {
        val result = PreTradeRiskCalculator.calculate(equityInput(stop = 105.0, target = 90.0))
        assertTrue((result as PreTradeRiskResult.Rejected).reason.contains("сторону"))
    }
    @Test fun rejectsSlippageThatWouldCreateNonPositiveExecutionPrices() {
        val input = equityInput().copy(slippageBasisPointsPerSide = 20_000.0)
        val result = PreTradeRiskCalculator.calculate(input)
        assertTrue(result is PreTradeRiskResult.Rejected)
        assertTrue((result as PreTradeRiskResult.Rejected).reason.contains("проскальзывания"))
    }

    @Test fun sizesShortFuturesPosition() {
        val spec = FuturesRiskSpec(1.0, 1.0, 120.0, "2026-12-17", "MOEX ISS", freshSpecTime)
        val input = PreTradeRiskInput(
            capital = 1_000.0, riskFraction = 0.02,
            instrumentType = RiskInstrumentType.FUTURE, direction = TradeDirection.SHORT,
            entryPrice = 100.0, stopPrice = 110.0, targetPrice = 80.0,
            slippageBasisPointsPerSide = 1.0,
            futuresSpec = spec, commissionPerContractPerSide = 1.2,
            currentTimeEpochMillis = testNow, asOfDate = "2026-11-25"
        )
        val result = PreTradeRiskCalculator.calculate(input)
        assertTrue(result is PreTradeRiskResult.Ready)
        val ready = result as PreTradeRiskResult.Ready
        assertEquals(1, ready.quantity)
        assertTrue(ready.estimatedMaximumLoss <= input.capital * input.riskFraction)
        assertTrue(ready.estimatedNetReward > 0.0)
    }

}
