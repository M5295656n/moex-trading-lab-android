package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * Reads only the generally available FORTS securities catalog.
 *
 * This endpoint can provide basic contract metadata, but it does not provide
 * the complete risk specification required by PreTradeRiskCalculator.
 */
class MoexPublicFuturesCatalogRepository(
    private val client: MoexIssTableClient
) {
    suspend fun load(
        secId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxPages: Int = DEFAULT_MAX_PAGES
    ): PublicFuturesMetadataResult {
        if (!secId.matches(Regex("[A-Za-z0-9._-]+"))) {
            return PublicFuturesMetadataResult.Rejected("Некорректный SECID фьючерса")
        }
        if (pageSize !in 1..MAX_PAGE_SIZE || maxPages <= 0) {
            return PublicFuturesMetadataResult.Rejected("Некорректные параметры загрузки каталога MOEX")
        }

        return try {
            val matches = mutableListOf<Map<String, String?>>()
            var fetchedAt = 0L
            var complete = false
            var page = 0

            while (page < maxPages) {
                val table = client.getTable(
                    endpoint = ENDPOINT,
                    query = mapOf(
                        "start" to (page * pageSize).toString(),
                        "limit" to pageSize.toString()
                    ),
                    tableName = TABLE_NAME
                )
                fetchedAt = maxOf(fetchedAt, table.fetchedAtEpochMillis)
                table.rows.forEach { row ->
                    val mapped = row.mapValues { (_, value) -> value.toPublicString() }
                    if (mapped["SECID"]?.trim()?.equals(secId, ignoreCase = true) == true) {
                        matches += mapped
                    }
                }
                page++
                if (table.rows.size < pageSize) {
                    complete = true
                    break
                }
            }

            if (!complete) {
                return PublicFuturesMetadataResult.Rejected(
                    "Публичный каталог MOEX ISS загружен не полностью; однозначность контракта не подтверждена"
                )
            }
            val uniqueMatches = matches.distinct()
            val row = uniqueMatches.singleOrNull()
                ?: return PublicFuturesMetadataResult.Rejected(
                    if (uniqueMatches.isEmpty()) {
                        "Контракт $secId не найден в публичном каталоге MOEX ISS"
                    } else {
                        "В публичном каталоге MOEX найдено несколько разных записей для $secId"
                    }
                )

            val minStep = row.number("MINSTEP")?.takeIf { it.isFinite() && it > 0.0 }
            val metadata = PublicFuturesMetadata(
                secId = secId,
                shortName = row["SHORTNAME"]?.takeIf(String::isNotBlank),
                fullName = row["SECNAME"]?.takeIf(String::isNotBlank),
                minStep = minStep,
                decimals = row["DECIMALS"]?.toIntOrNull()?.takeIf { it >= 0 },
                previousSettlementPrice = row.number("PREVSETTLEPRICE")
                    ?.takeIf { it.isFinite() && it > 0.0 },
                lastTradeDate = row.date("LASTTRADEDATE"),
                lastDeliveryDate = row.date("LASTDELDATE"),
                source = "MOEX ISS public FORTS securities catalog",
                fetchedAtEpochMillis = fetchedAt
            )
            PublicFuturesMetadataResult.Ready(metadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PublicFuturesMetadataResult.Rejected(
                "Не удалось получить публичные сведения MOEX ISS: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    private fun Map<String, String?>.number(key: String): Double? =
        this[key]?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun Map<String, String?>.date(key: String): LocalDate? =
        this[key]?.takeIf(String::isNotBlank)?.let { value ->
            runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
        }

    companion object {
        const val ENDPOINT = "engines/futures/markets/forts/securities"
        const val TABLE_NAME = "securities"
        const val DEFAULT_PAGE_SIZE = 500
        const val DEFAULT_MAX_PAGES = 200
        const val MAX_PAGE_SIZE = 500
    }
}

sealed interface PublicFuturesMetadataResult {
    data class Ready(val metadata: PublicFuturesMetadata) : PublicFuturesMetadataResult
    data class Rejected(val reason: String) : PublicFuturesMetadataResult
}

/**
 * Public metadata only. No tick monetary value or margin is inferred here.
 */
data class PublicFuturesMetadata(
    val secId: String,
    val shortName: String?,
    val fullName: String?,
    val minStep: Double?,
    val decimals: Int?,
    val previousSettlementPrice: Double?,
    val lastTradeDate: LocalDate?,
    val lastDeliveryDate: LocalDate?,
    val source: String,
    val fetchedAtEpochMillis: Long
)

private fun JsonElement?.toPublicString(): String? =
    this?.takeUnless(JsonElement::isJsonNull)?.let { element ->
        runCatching { element.asString }.getOrElse { element.toString().trim('"') }
    }
