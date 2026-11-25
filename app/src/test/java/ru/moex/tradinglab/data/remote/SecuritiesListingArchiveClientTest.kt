package ru.moex.tradinglab.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SecuritiesListingArchiveClientTest {
    @Test
    fun downloadsZipAndCalculatesSha256() = runBlocking {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)
        val client = SecuritiesListingArchiveClient(
            httpClient = fakeHttpClient(200, bytes),
            archiveUrl = "https://iss.moex.com/file/stock_boardsecid/SL.ZIP"
        )
        val result = client.download()
        assertTrue(result.bytes.contentEquals(bytes))
        assertEquals(64, result.sha256.length)
        assertEquals(SecuritiesListingArchiveClient.DEFAULT_ARCHIVE_URL, result.sourceUrl)
        assertTrue(result.fetchedAtEpochMillis > 0)
    }

    @Test
    fun rejectsHttpError() = runBlocking {
        val client = SecuritiesListingArchiveClient(
            httpClient = fakeHttpClient(503, "unavailable".toByteArray())
        )
        try {
            client.download()
            throw AssertionError("Expected HTTP failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("503"))
        }
    }

    @Test
    fun rejectsHtmlBodyEvenWhenHttpStatusIs200() = runBlocking {
        val client = SecuritiesListingArchiveClient(
            httpClient = fakeHttpClient(200, "<html>login</html>".toByteArray())
        )
        try {
            client.download()
            throw AssertionError("Expected invalid archive failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("ZIP"))
        }
    }

    @Test
    fun refusesNonHttpsUrl() = runBlocking {
        val client = SecuritiesListingArchiveClient(
            httpClient = fakeHttpClient(200, byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
            archiveUrl = "http://iss.moex.com/file/stock_boardsecid/SL.ZIP"
        )
        try {
            client.download()
            throw AssertionError("Expected HTTPS validation failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("HTTPS"))
        }
    }

    private fun fakeHttpClient(status: Int, bytes: ByteArray) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "Service Unavailable")
                .body(bytes.toResponseBody())
                .build()
        }
        .build()
}
