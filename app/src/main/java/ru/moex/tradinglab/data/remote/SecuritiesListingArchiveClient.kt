package ru.moex.tradinglab.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface SecuritiesListingArchiveSource {
    suspend fun download(): DownloadedSecuritiesListingArchive
}

data class DownloadedSecuritiesListingArchive(
    val bytes: ByteArray,
    val sha256: String,
    val fetchedAtEpochMillis: Long,
    val sourceUrl: String
)

/** Read-only downloader. Default TLS verification is intentionally retained. */
class SecuritiesListingArchiveClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val archiveUrl: String = DEFAULT_ARCHIVE_URL
 ) : SecuritiesListingArchiveSource {
    override suspend fun download(): DownloadedSecuritiesListingArchive = withContext(Dispatchers.IO) {
        val url = archiveUrl.toHttpUrl()
        require(url.isHttps) { "SecuritiesListing archive must use HTTPS" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/zip, application/octet-stream")
            .header("User-Agent", "MOEX-Trading-Lab-Android/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("MOEX SecuritiesListing returned HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("MOEX SecuritiesListing response has no body")
            if (body.contentLength() > MAX_ARCHIVE_BYTES) {
                throw IOException("MOEX SecuritiesListing ZIP exceeds the size limit")
            }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_ARCHIVE_BYTES) {
                        throw IOException("MOEX SecuritiesListing ZIP exceeds the size limit")
                    }
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
                throw IOException("MOEX SecuritiesListing did not return a ZIP archive")
            }
            val sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            DownloadedSecuritiesListingArchive(
                bytes = bytes,
                sha256 = sha256,
                fetchedAtEpochMillis = System.currentTimeMillis(),
                sourceUrl = url.toString()
            )
        }
    }

    companion object {
        const val DEFAULT_ARCHIVE_URL = "https://iss.moex.com/file/stock_boardsecid/SL.ZIP"
        private const val MAX_ARCHIVE_BYTES = 20L * 1024 * 1024

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
