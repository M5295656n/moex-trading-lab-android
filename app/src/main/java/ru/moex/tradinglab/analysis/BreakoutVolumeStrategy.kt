package ru.moex.tradinglab.analysis

/**
 * Outcome of evaluating a close-confirmed breakout against the highs of
 * preceding candles. No broker orders are created or submitted.
 */
enum class BreakoutSignalStatus {
    INSUFFICIENT_DATA,
    NO_BREAKOUT,
    BREAKOUT_WITHOUT_VOLUME_CONFIRMATION,
    CONFIRMED
}

data class BreakoutSignalAssessment(
    val status: BreakoutSignalStatus,
    val breakoutLevel: Double?,
    val close: Double?,
    val relativeVolume: Double?,
    val requiredRelativeVolume: Double,
    val lookbackBars: Int
)

/**
 * Donchian-style close breakout with a relative-volume filter.
 *
 * Candles must be ordered oldest-to-newest. The last candle is the candidate
 * signal candle and is deliberately excluded from the breakout-high window
 * and volume baseline. Missing/invalid observations are never imputed.
 */
object BreakoutVolumeStrategy {
    fun evaluate(
        candles: List<AnalysisCandle>,
        lookbackBars: Int = 20,
        volumeBaselineBars: Int = 20,
        minimumRelativeVolume: Double = 1.5
    ): BreakoutSignalAssessment {
        require(lookbackBars > 0) { "lookbackBars must be positive" }
        require(volumeBaselineBars > 0) { "volumeBaselineBars must be positive" }
        require(minimumRelativeVolume.isFinite() && minimumRelativeVolume > 0.0) {
            "minimumRelativeVolume must be finite and positive"
        }

        val requiredHistory = maxOf(lookbackBars, volumeBaselineBars) + 1
        if (candles.size < requiredHistory) {
            return insufficient(minimumRelativeVolume, lookbackBars)
        }

        val current = candles.last()
        val close = current.close?.takeIf { it.isFinite() && it > 0.0 }
            ?: return insufficient(minimumRelativeVolume, lookbackBars)

        // The current candle is excluded: only fully completed prior bars set the level.
        val previousHighs = candles
            .drop(candles.size - lookbackBars - 1)
            .dropLast(1)
            .map { it.high?.takeIf { value -> value.isFinite() && value > 0.0 } ?: 
                return insufficient(minimumRelativeVolume, lookbackBars) }

        val breakoutLevel = previousHighs.maxOrNull()
            ?: return insufficient(minimumRelativeVolume, lookbackBars)

        if (close <= breakoutLevel) {
            return BreakoutSignalAssessment(
                status = BreakoutSignalStatus.NO_BREAKOUT,
                breakoutLevel = breakoutLevel,
                close = close,
                relativeVolume = null,
                requiredRelativeVolume = minimumRelativeVolume,
                lookbackBars = lookbackBars
            )
        }

        val volume = MarketRegimeAnalyzer.assessVolume(candles, volumeBaselineBars)
            ?: return BreakoutSignalAssessment(
                status = BreakoutSignalStatus.INSUFFICIENT_DATA,
                breakoutLevel = breakoutLevel,
                close = close,
                relativeVolume = null,
                requiredRelativeVolume = minimumRelativeVolume,
                lookbackBars = lookbackBars
            )

        val status = if (volume.relativeVolume >= minimumRelativeVolume) {
            BreakoutSignalStatus.CONFIRMED
        } else {
            BreakoutSignalStatus.BREAKOUT_WITHOUT_VOLUME_CONFIRMATION
        }

        return BreakoutSignalAssessment(
            status = status,
            breakoutLevel = breakoutLevel,
            close = close,
            relativeVolume = volume.relativeVolume,
            requiredRelativeVolume = minimumRelativeVolume,
            lookbackBars = lookbackBars
        )
    }

    private fun insufficient(
        minimumRelativeVolume: Double,
        lookbackBars: Int
    ) = BreakoutSignalAssessment(
        status = BreakoutSignalStatus.INSUFFICIENT_DATA,
        breakoutLevel = null,
        close = null,
        relativeVolume = null,
        requiredRelativeVolume = minimumRelativeVolume,
        lookbackBars = lookbackBars
    )
}
