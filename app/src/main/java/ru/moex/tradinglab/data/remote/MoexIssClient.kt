package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal read-only client for the public MOEX ISS HTTP API.
 *
 * This client does not place orders and deliberately returns source metadata
 * alongside the decoded table. Public ISS data may be delayed and subject to
 * MOEX information-use terms.
 */
class MoexIssClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: HttpUrl = "https://iss.moex.com/iss/".toHttpUrl()
) {
    suspend fun getTable(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        tableName: String = endpoint.substringAfterLast('/').substringBefore('.')
    ): IssTable = withContext(Dispatchers.IO) {
        val normalizedEndpoint = endpoint.trimStart('/')
        require(normalizedEndpoint.isNotBlank()) { "ISS endpoint must not be blank" }
        require(!normalizedEndpoint.contains("..")) { "Invalid ISS endpoint" }
        require(tableName.matches(Regex("[A-Za-z0-9_.]+"))) { "Invalid ISS table name" }

        val urlBuilder = baseUrl.newBuilder()
            .addPathSegments(normalizedEndpoint)
            .addQueryParameter("iss.meta", "off")
            .addQueryParameter("iss.json", "extended")
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("User-Agent", "MOEX-Trading-Lab-Android/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw MoexIssException.Http(response.code, body.take(500))
            }
            if (body.isBlank()) throw MoexIssException.InvalidResponse("Empty ISS response")
            IssJsonDecoder.decode(body, normalizedEndpoint, tableName = tableName)
        }
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

data class IssTable(
    val endpoint: String,
    val columns: List<String>,
    val rows: List<Map<String, JsonElement?>>,
    val fetchedAtEpochMillis: Long
) {
    fun value(row: Map<String, JsonElement?>, column: String): JsonElement? =
        row[column]
}

sealed class MoexIssException(message: String, cause: Throwable? = null) :
    IOException(message, cause) {
    data class Http(val statusCode: Int, val responseExcerpt: String) :
        MoexIssException("MOEX ISS returned HTTP $statusCode: $responseExcerpt")

    class InvalidResponse(message: String) : MoexIssException(message)
}

internal object IssJsonDecoder {
    fun decode(
        json: String,
        endpoint: String,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
        tableName: String = endpoint.substringAfterLast('/').substringBefore('.')
    ): IssTable {
        val root = try {
            JsonParser.parseString(json)
        } catch (error: Exception) {
            throw MoexIssException.InvalidResponse("ISS response is not valid JSON")
        }

        val table = findTable(root, tableName)
            ?: throw MoexIssException.InvalidResponse("No table '$tableName' in ISS response")

        // MOEX ISS with iss.json=extended returns [charsetinfo, {tableName: [...]}].
        // The legacy non-extended representation uses {iss:{tableName:{columns,data}}}.
        if (table.isJsonArray) {
            val records = table.asJsonArray
            val names = linkedSetOf<String>()
            records.forEach { record ->
                if (!record.isJsonObject) {
                    throw MoexIssException.InvalidResponse("Non-object row in '$tableName'")
                }
                record.asJsonObject.keySet().forEach(names::add)
            }
            val columns = names.toList()
            val rows = records.map { record ->
                val values = record.asJsonObject
                columns.associateWith { name ->
                    values.get(name)?.takeUnless { it.isJsonNull }
                }
            }
            return IssTable(endpoint, columns, rows, fetchedAtEpochMillis)
        }

        if (table.isJsonObject) {
            val tableObject = table.asJsonObject
            val columns = tableObject.getAsJsonArray("columns")
                ?: throw MoexIssException.InvalidResponse("Missing columns for '$tableName'")
            val data = tableObject.getAsJsonArray("data")
                ?: throw MoexIssException.InvalidResponse("Missing data for '$tableName'")
            val names = columns.map { it.asString }
            val rows = data.map { rowElement ->
                val values = rowElement.asJsonArray
                if (values.size() != names.size) {
                    throw MoexIssException.InvalidResponse("Column/value count mismatch in '$tableName'")
                }
                names.indices.associate { index ->
                    names[index] to values[index].takeUnless { it.isJsonNull }
                }
            }
            return IssTable(endpoint, names, rows, fetchedAtEpochMillis)
        }

        throw MoexIssException.InvalidResponse("Unsupported table format for '$tableName'")
    }

    private fun findTable(root: JsonElement, tableName: String): JsonElement? {
        val candidates = when {
            root.isJsonArray -> root.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            root.isJsonObject -> {
                val rootObject = root.asJsonObject
                val nestedIss = rootObject.getAsJsonObject("iss")
                listOfNotNull(nestedIss, rootObject)
            }
            else -> emptyList()
        }
        return candidates.asSequence()
            .mapNotNull { objectValue ->
                objectValue.entrySet().firstOrNull { it.key.equals(tableName, ignoreCase = true) }?.value
            }
            .firstOrNull()
    }
}
