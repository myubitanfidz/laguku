package com.laguku.player.lyrics

import com.laguku.player.data.SimpleJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Lightweight on-demand LRCLIB client using [HttpURLConnection] and [SimpleJson],
 * strictly bounded to 256 KB responses and 6s/8s timeouts with zero external dependencies.
 */
object OnlineLyricFetcher {

    sealed class FetchResult {
        data class Found(
            val lrcContent: String,
            val isSynced: Boolean,
            val plainText: String
        ) : FetchResult()

        data object NotFound : FetchResult()
        data class NetworkError(val message: String) : FetchResult()
    }

    const val MAX_RESPONSE_BYTES = 256 * 1024
    const val CONNECT_TIMEOUT_MS = 6_000
    const val READ_TIMEOUT_MS = 8_000
    const val USER_AGENT = "Laguku/1.0"
    const val GET_DURATION_TOLERANCE_SEC = 3
    const val SEARCH_DURATION_TOLERANCE_SEC = 4

    fun fetch(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        fileNameFallback: String = ""
    ): FetchResult {
        val clean = LyricQuerySanitizer.sanitize(title, artist, album, durationMs, fileNameFallback)
        if (clean.title.isBlank()) return FetchResult.NotFound

        var getNetworkError: String? = null
        var plainFallback: FetchResult.Found? = null

        if (clean.artist.isNotBlank()) {
            val getUrl = buildGetUrl(clean)
            when (val res = executeHttpGet(getUrl)) {
                is HttpOutcome.Success -> {
                    when (val parsed = parseGetResponse(res.body, clean.durationSec)) {
                        is FetchResult.Found -> {
                            if (parsed.isSynced) return parsed
                            plainFallback = parsed
                        }
                        else -> Unit
                    }
                }
                is HttpOutcome.Error -> getNetworkError = res.message
            }
        }

        val searchQuery = if (clean.artist.isNotBlank()) "${clean.artist} ${clean.title}" else clean.title
        val searchUrl = "https://lrclib.net/api/search?q=${encode(searchQuery)}"
        return when (val res = executeHttpGet(searchUrl)) {
            is HttpOutcome.Success -> {
                val parsed = parseSearchResponse(res.body, clean.durationSec)
                if (parsed is FetchResult.Found) parsed else plainFallback ?: FetchResult.NotFound
            }
            is HttpOutcome.Error -> plainFallback ?: FetchResult.NetworkError(getNetworkError ?: res.message)
        }
    }

    fun parseGetResponse(jsonText: String, expectedDurationSec: Int): FetchResult {
        val obj = SimpleJson.parseObject(jsonText) ?: return FetchResult.NotFound
        return extractFromCandidate(obj, expectedDurationSec, GET_DURATION_TOLERANCE_SEC)
            ?: FetchResult.NotFound
    }

    fun parseSearchResponse(jsonText: String, expectedDurationSec: Int): FetchResult {
        val list = SimpleJson.parse(jsonText) as? List<*> ?: return FetchResult.NotFound
        var bestSynced: Pair<Int, FetchResult.Found>? = null
        var bestPlain: Pair<Int, FetchResult.Found>? = null

        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val candidate = extractFromCandidate(map, expectedDurationSec, SEARCH_DURATION_TOLERANCE_SEC)
                ?: continue
            val candDuration = SimpleJson.asDouble(map["duration"], -1.0).toInt()
            val delta = if (expectedDurationSec > 0 && candDuration > 0) {
                abs(candDuration - expectedDurationSec)
            } else {
                0
            }

            if (candidate.isSynced) {
                if (bestSynced == null || delta < bestSynced.first) {
                    bestSynced = delta to candidate
                }
            } else if (bestPlain == null || delta < bestPlain.first) {
                bestPlain = delta to candidate
            }
        }

        return bestSynced?.second ?: bestPlain?.second ?: FetchResult.NotFound
    }

    private fun extractFromCandidate(
        obj: Map<*, *>,
        expectedDurationSec: Int,
        maxToleranceSec: Int
    ): FetchResult.Found? {
        val instrumental = SimpleJson.asBoolean(obj["instrumental"], false)
        if (instrumental) return null

        val durationSec = SimpleJson.asDouble(obj["duration"], 0.0).toInt()
        if (expectedDurationSec > 0 && durationSec > 0 && abs(durationSec - expectedDurationSec) > maxToleranceSec) {
            return null
        }

        val synced = obj["syncedLyrics"]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val plain = obj["plainLyrics"]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        return when {
            synced != null -> FetchResult.Found(lrcContent = synced, isSynced = true, plainText = plain ?: synced)
            plain != null -> FetchResult.Found(lrcContent = plain, isSynced = false, plainText = plain)
            else -> null
        }
    }

    private fun buildGetUrl(clean: CleanLyricQuery): String {
        val sb = StringBuilder("https://lrclib.net/api/get?track_name=").append(encode(clean.title))
        if (clean.artist.isNotBlank()) {
            sb.append("&artist_name=").append(encode(clean.artist))
        }
        if (clean.album.isNotBlank()) {
            sb.append("&album_name=").append(encode(clean.album))
        }
        if (clean.durationSec > 0) {
            sb.append("&duration=").append(clean.durationSec)
        }
        return sb.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private sealed class HttpOutcome {
        data class Success(val body: String) : HttpOutcome()
        data class Error(val message: String) : HttpOutcome()
    }

    private fun executeHttpGet(urlStr: String): HttpOutcome {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return HttpOutcome.Success("null")
            }
            if (code !in 200..299) {
                return HttpOutcome.Error("HTTP $code")
            }
            val body = conn.inputStream.use { readBoundedUtf8(it, MAX_RESPONSE_BYTES) }
                ?: return HttpOutcome.Error("Respons melebihi batas")
            HttpOutcome.Success(body)
        } catch (e: Exception) {
            HttpOutcome.Error(e.message ?: "Gagal terhubung")
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBoundedUtf8(stream: InputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream(4096)
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
