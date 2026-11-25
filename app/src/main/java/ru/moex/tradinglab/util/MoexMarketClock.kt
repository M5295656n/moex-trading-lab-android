package ru.moex.tradinglab.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Trading-date helper for MOEX. Device locale/time zone must not shift
 * the accepted backtest date around the Moscow midnight boundary.
 */
object MoexMarketClock {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    fun today(epochMillis: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
