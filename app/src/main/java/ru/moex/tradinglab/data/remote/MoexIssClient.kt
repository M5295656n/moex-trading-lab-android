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
        query: Map<String, String> = emptyMap()
    ): IssTable = withContext(Dispatchers.IO) {
        val normalizedEndpoint = endpoint.trimStart('/')
        require(normalizedEndpoint.isNotBlank()) { "ISS endpoint must not be blank" }
        require(!normalizedEndpoint.contains("..")) { "Invalid ISS endpoint" }

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
            IssJsonDecoder.decode(body, normalizedEndpoint)
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
        fetchedAtEpochMillis: Long = System.currentTimeMillis()
    ): IssTable {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (error: Exception) {
            throw MoexIssException.InvalidResponse("ISS response is not valid JSON")
        }

        val iss = root.getAsJsonObject("iss")
            ?: throw MoexIssException.InvalidResponse("Missing ISS metadata object")
        val tableName = endpoint.substringAfterLast('/').substringBefore('.')
        val table = findTable(iss, tableName)
            ?: throw MoexIssException.InvalidResponse("No table '$tableName' in ISS response")

        val columns = table.getAsJsonArray("columns")
            ?: throw MoexIssException.InvalidResponse("Missing columns for '$tableName'")
        val data = table.getAsJsonArray("data")
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

    private fun findTable(iss: JsonObject, tableName: String): JsonObject? {
        iss.getAsJsonObject(tableName)?.let { return it }
        return iss.entrySet()
            .firstOrNull { it.key.equals(tableName, ignoreCase = true) }
            ?.value?.takeIf { it.isJsonObject }?.asJsonObject
    }
}
