package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.moex.tradinglab.analysis.FuturesRiskSpec

/**
 * Read-only loader for the three MOEX ISS Reference Data tables that describe
 * futures contracts, their trading parameters and their risk parameters.
 *
 * A specification is returned only when the tables can be joined unambiguously
 * and all required values have source timestamps and valid effective dates.
 */
class MoexFuturesSpecificationRepository(
    private val client: MoexIssTableClient
) {
    suspend fun load(
        secId: String,
        asOfDate: LocalDate = LocalDate.now(MOSCOW),
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxPages: Int = DEFAULT_MAX_PAGES
    ): FuturesSpecificationLoadResult {
        if (!secId.matches(Regex("[A-Za-z0-9._-]+"))) {
            return FuturesSpecificationLoadResult.Rejected("Некорректный SECID фьючерса")
        }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be between 1 and $MAX_PAGE_SIZE" }
        require(maxPages > 0) { "maxPages must be positive" }

        return try {
            val securities = loadTable(
                SECURITIES_ENDPOINT, "securities", asOfDate, pageSize, maxPages
            )
            val params = loadTable(
                PARAMS_ENDPOINT, "params", asOfDate, pageSize, maxPages
            )
            val risks = loadTable(
                RISKS_ENDPOINT, "risks", asOfDate, pageSize, maxPages
            )

            if (!securities.complete || !params.complete || !risks.complete) {
                return FuturesSpecificationLoadResult.Rejected(
                    "Справочники MOEX ISS загружены не полностью; расчёт фьючерса заблокирован"
                )
            }

            FuturesSpecificationMapper.map(
                secId = secId,
                asOfDate = asOfDate,
                securitiesRows = securities.rows,
                paramsRows = params.rows,
                risksRows = risks.rows,
                receivedAtEpochMillis = maxOf(
                    securities.receivedAtEpochMillis,
                    params.receivedAtEpochMillis,
                    risks.receivedAtEpochMillis
                )
            )
        } catch (error: Exception) {
            FuturesSpecificationLoadResult.Rejected(
                "Не удалось получить спецификацию MOEX ISS: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    private suspend fun loadTable(
        endpoint: String,
        tableName: String,
        date: LocalDate,
        pageSize: Int,
        maxPages: Int
    ): LoadedTable {
        val rows = mutableListOf<Map<String, String?>>()
        var receivedAt = 0L
        var complete = false
        var page = 0

        while (page < maxPages) {
            val table = client.getTable(
                endpoint = endpoint,
                query = mapOf(
                    "date" to date.toString(),
                    "start" to (page * pageSize).toString(),
                    "limit" to pageSize.toString()
                ),
                tableName = tableName
            )
            receivedAt = maxOf(receivedAt, table.fetchedAtEpochMillis)
            rows += table.rows.map { row ->
                row.mapValues { (_, value) -> value.toIssString() }
            }
            page++

            if (table.rows.size < pageSize) {
                complete = true
                break
            }
        }
        return LoadedTable(rows, receivedAt, complete)
    }

    private data class LoadedTable(
        val rows: List<Map<String, String?>>,
        val receivedAtEpochMillis: Long,
        val complete: Boolean
    )

    companion object {
        const val SECURITIES_ENDPOINT =
            "referencedata/engines/futures/markets/forts/securities"
        const val PARAMS_ENDPOINT =
            "referencedata/engines/futures/markets/forts/params"
        const val RISKS_ENDPOINT =
            "referencedata/engines/futures/markets/forts/risks"

        const val DEFAULT_PAGE_SIZE = 1_000
        const val DEFAULT_MAX_PAGES = 20
        const val MAX_PAGE_SIZE = 1_000
    }
}

sealed interface FuturesSpecificationLoadResult {
    data class Ready(val value: LoadedFuturesSpecification) : FuturesSpecificationLoadResult
    data class Rejected(val reason: String) : FuturesSpecificationLoadResult
}

data class LoadedFuturesSpecification(
    val secId: String,
    val specification: FuturesRiskSpec,
    val referenceDate: LocalDate,
    val receivedAtEpochMillis: Long,
    val sourceDocumentTimes: Map<String, Long>,
    val tickSource: String,
    val marginSource: String,
    val expirySource: String
)

/**
 * Maps MOEX's documented FO/CP fields into the calculator's conservative
 * specification. Unknown/missing fields are never replaced with zero.
 */
internal object FuturesSpecificationMapper {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val localDateTimeFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    )

    fun map(
        secId: String,
        asOfDate: LocalDate,
        securitiesRows: List<Map<String, String?>>,
        paramsRows: List<Map<String, String?>>,
        risksRows: List<Map<String, String?>>,
        receivedAtEpochMillis: Long
    ): FuturesSpecificationLoadResult {
        val security = uniqueMatch("справочнике инструментов", secId, securitiesRows)
            ?: return FuturesSpecificationLoadResult.Rejected(
                "Для $secId нет однозначной записи в справочнике фьючерсов MOEX"
            )
        val params = uniqueMatch("параметрах контракта", secId, paramsRows)
            ?: return FuturesSpecificationLoadResult.Rejected(
                "Для $secId нет однозначной записи с шагом цены в MOEX ISS"
            )
        val risk = uniqueMatch("риск-параметрах", secId, risksRows)
            ?: return FuturesSpecificationLoadResult.Rejected(
                "Для $secId нет однозначной записи в риск-параметрах MOEX"
            )

        val rows = linkedMapOf(
            "securities" to security,
            "params" to params,
            "risks" to risk
        )
        val documentTimes = linkedMapOf<String, Long>()
        rows.forEach { (table, row) ->
            val docDate = row["DOC_DATE"]?.takeIf(String::isNotBlank)
                ?: return FuturesSpecificationLoadResult.Rejected(
                    "В таблице $table отсутствует DOC_DATE; актуальность спецификации не подтверждена"
                )
            val timestamp = parseTimestamp(docDate)
                ?: return FuturesSpecificationLoadResult.Rejected(
                    "Не удалось разобрать DOC_DATE='$docDate' в таблице $table"
                )
            documentTimes[table] = timestamp

            val effectiveDateText = row["EFFECTIVE_DATE"]?.takeIf(String::isNotBlank)
                ?: return FuturesSpecificationLoadResult.Rejected(
                    "В таблице $table отсутствует EFFECTIVE_DATE"
                )
            val effectiveDate = parseDate(effectiveDateText)
                ?: return FuturesSpecificationLoadResult.Rejected(
                    "Некорректный EFFECTIVE_DATE='$effectiveDateText' в таблице $table"
                )
            if (effectiveDate.isAfter(asOfDate)) {
                return FuturesSpecificationLoadResult.Rejected(
                    "Параметры из таблицы $table вступают в силу только $effectiveDate"
                )
            }
        }

        val paramsTick = params.number("TICK")
        val securityTick = security.number("MIN_STEP")
        val tick = consistentValue("шаг цены", paramsTick, securityTick)
            ?: return FuturesSpecificationLoadResult.Rejected(
                "В MOEX ISS отсутствует однозначный шаг цены (TICK / MIN_STEP)"
            )

        val paramsTickValue = params.number("TICK_PRICE")
        val securityTickValue = security.number("MIN_STEP_PRICE")
        val tickValue = consistentValue("стоимость шага", paramsTickValue, securityTickValue)
            ?: return FuturesSpecificationLoadResult.Rejected(
                "В MOEX ISS отсутствует однозначная стоимость шага цены (TICK_PRICE / MIN_STEP_PRICE)"
            )

        val deposit = params.number("DEPOSIT")
        val riskMargin = risk.number("MARGIN")
        val marginCandidates = listOfNotNull(deposit, riskMargin)
            .filter { it.isFinite() && it > 0.0 }
        if (marginCandidates.isEmpty()) {
            return FuturesSpecificationLoadResult.Rejected(
                "В MOEX ISS отсутствует положительное ГО (DEPOSIT / MARGIN)"
            )
        }
        // When the two official blocks disagree, using the higher margin is
        // conservative for the capital constraint in the position calculator.
        val initialMargin = marginCandidates.maxOrNull()!!

        val expiryCandidates = listOfNotNull(
            security.date("END_DATE"),
            security.date("EXP_DATE"),
            risk.date("EXP_DATE")
        )
        if (expiryCandidates.isEmpty()) {
            return FuturesSpecificationLoadResult.Rejected(
                "В MOEX ISS отсутствует дата последней торговли/исполнения контракта"
            )
        }
        // END_DATE is the last trading date; selecting the earliest available
        // contract boundary prevents sizing a contract beyond its trading life.
        val expiration = expiryCandidates.minOrNull()!!
        if (expiration.isBefore(asOfDate)) {
            return FuturesSpecificationLoadResult.Rejected(
                "Контракт $secId истёк или завершил торги $expiration"
            )
        }

        val fetchedAt = documentTimes.values.minOrNull()
            ?: return FuturesSpecificationLoadResult.Rejected("Не определено время формирования спецификации")
        if (fetchedAt <= 0L) {
            return FuturesSpecificationLoadResult.Rejected("Некорректное время формирования спецификации")
        }

        val spec = runCatching {
            FuturesRiskSpec(
                tickSize = tick,
                tickValue = tickValue,
                initialMarginPerContract = initialMargin,
                expirationDate = expiration.toString(),
                source = "MOEX ISS Reference Data (securities + params + risks)",
                fetchedAtEpochMillis = fetchedAt
            )
        }.getOrElse {
            return FuturesSpecificationLoadResult.Rejected(
                "Спецификация MOEX содержит недопустимые значения: ${it.message}"
            )
        }

        val tickSource = buildList {
            if (paramsTick != null || paramsTickValue != null) add("params")
            if (securityTick != null || securityTickValue != null) add("securities")
        }.joinToString(" + ")
        val marginSource = when {
            deposit != null && riskMargin != null && deposit != riskMargin -> "max(DEPOSIT, MARGIN)"
            deposit != null && riskMargin != null -> "DEPOSIT = MARGIN"
            deposit != null -> "DEPOSIT"
            else -> "MARGIN"
        }
        val expirySource = when {
            security.date("END_DATE") == expiration -> "securities.END_DATE"
            security.date("EXP_DATE") == expiration -> "securities.EXP_DATE"
            else -> "risks.EXP_DATE"
        }

        return FuturesSpecificationLoadResult.Ready(
            LoadedFuturesSpecification(
                secId = secId,
                specification = spec,
                referenceDate = asOfDate,
                receivedAtEpochMillis = receivedAtEpochMillis,
                sourceDocumentTimes = documentTimes,
                tickSource = tickSource,
                marginSource = marginSource,
                expirySource = expirySource
            )
        )
    }

    private fun uniqueMatch(
        tableName: String,
        secId: String,
        rows: List<Map<String, String?>>
    ): Map<String, String?>? {
        val matches = rows.filter { row ->
            listOf("SECID", "TICKER", "CONTRACT", "SHORT_TICKER")
                .any { key -> row[key]?.trim()?.equals(secId, ignoreCase = true) == true }
        }
        if (matches.isEmpty()) return null
        val distinct = matches.distinctBy { row ->
            listOf(
                row["TICKER"], row["CONTRACT"], row["SHORT_TICKER"],
                row["TICK"], row["TICK_PRICE"], row["MIN_STEP"],
                row["MIN_STEP_PRICE"], row["DEPOSIT"], row["MARGIN"],
                row["END_DATE"], row["EXP_DATE"], row["DOC_DATE"], row["EFFECTIVE_DATE"]
            ).joinToString("|")
        }
        return distinct.singleOrNull()
    }

    private fun consistentValue(label: String, first: Double?, second: Double?): Double? {
        val validFirst = first?.takeIf { it.isFinite() && it > 0.0 }
        val validSecond = second?.takeIf { it.isFinite() && it > 0.0 }
        if (validFirst == null) return validSecond
        if (validSecond == null) return validFirst
        val tolerance = maxOf(1e-9, maxOf(kotlin.math.abs(validFirst), kotlin.math.abs(validSecond)) * 1e-8)
        return if (kotlin.math.abs(validFirst - validSecond) <= tolerance) validFirst else null
    }

    private fun Map<String, String?>.number(key: String): Double? =
        this[key]?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun Map<String, String?>.date(key: String): LocalDate? =
        this[key]?.takeIf(String::isNotBlank)?.let(::parseDate)

    private fun parseDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

    private fun parseTimestamp(value: String): Long? {
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        val normalized = value.trim().replace('T', ' ')
        localDateTimeFormatters.forEach { formatter ->
            runCatching {
                LocalDateTime.parse(normalized, formatter).atZone(moscow).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return parseDate(value)?.atStartOfDay(moscow)?.toInstant()?.toEpochMilli()
    }
}

private fun JsonElement?.toIssString(): String? =
    this?.takeUnless(JsonElement::isJsonNull)?.let { element ->
        runCatching { element.asString }.getOrElse { element.toString().trim('"') }
    }

private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
