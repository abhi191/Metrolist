package com.metrolist.music.sonos

import android.os.SystemClock
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves YouTube audio to Sonos over plain LAN HTTP.
 *
 * Sonos rejects googlevideo URLs (no file extension, signed query, IP-bound), so the
 * speaker fetches `http://<phone>:<port>/<token>/<videoId>.m4a` and this server resolves
 * the stream on demand and relays it in bounded Range chunks, which also avoids throttling.
 */
class SonosStreamServer(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val resolve: suspend (videoId: String) -> InnerTubeXPlayer.PlaybackData?,
) {
    private class ResolvedStream(
        val url: String,
        val headers: Map<String, String>,
        val contentLength: Long,
        val expiresAt: Long,
    )

    private val token = UUID.randomUUID().toString().replace("-", "").take(16)
    private val streams = ConcurrentHashMap<String, ResolvedStream>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    @Synchronized
    fun start() {
        if (isRunning) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        serverSocket = socket
        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isActive && !socket.isClosed) {
                    val client =
                        try {
                            socket.accept()
                        } catch (_: IOException) {
                            break
                        }
                    launch(Dispatchers.IO) { client.use { handle(it) } }
                }
            }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        streams.clear()
    }

    /** URL the speaker at [speakerHost] can reach, using the interface that routes to it. */
    fun urlFor(
        speakerHost: String,
        videoId: String,
    ): String? {
        val port = serverSocket?.localPort ?: return null
        val localHost = localAddressFacing(speakerHost) ?: return null
        return "http://$localHost:$port/$token/$videoId.m4a"
    }

    fun invalidate(videoId: String) {
        streams.remove(videoId)
    }

    private suspend fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val requestLine = input.readHeaderLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readHeaderLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)
            val segments = parts.getOrNull(1)?.trim('/')?.split('/').orEmpty()
            if ((method != "GET" && method != "HEAD") || segments.size != 2 || segments[0] != token) {
                output.writeStatus(404, "Not Found")
                return
            }
            val videoId = segments[1].substringBeforeLast(".m4a")
            val stream = stream(videoId)
            if (stream == null) {
                output.writeStatus(502, "Bad Gateway")
                return
            }
            serve(videoId, stream, headers["range"], sendBody = method == "GET", output = output)
        } catch (e: IOException) {
            // Sonos drops connections routinely when seeking or re-buffering.
            Timber.v(e, "Sonos stream connection closed")
        } catch (e: Exception) {
            Timber.e(e, "Sonos stream request failed")
        }
    }

    private suspend fun serve(
        videoId: String,
        initial: ResolvedStream,
        rangeHeader: String?,
        sendBody: Boolean,
        output: OutputStream,
    ) {
        var stream = initial
        val total = stream.contentLength
        val range = parseRange(rangeHeader, total)
        if (range == null) {
            output.writeStatus(416, "Range Not Satisfiable", "Content-Range: bytes */$total")
            return
        }
        val (start, end) = range
        val partial = rangeHeader != null
        val head =
            buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: audio/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ${end - start + 1}\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
                append("Connection: close\r\n\r\n")
            }
        output.write(head.toByteArray())
        output.flush()
        if (!sendBody) return

        var position = start
        var retried = false
        val buffer = ByteArray(64 * 1024)
        while (position <= end) {
            val chunkEnd = minOf(position + CHUNK_BYTES - 1, end)
            val response = upstream(stream, position, chunkEnd)
            response.use {
                if (it.code == 403 && !retried) {
                    retried = true
                    streams.remove(videoId)
                    stream = stream(videoId) ?: throw IOException("Could not refresh stream for $videoId")
                    return@use
                }
                if (it.code != 206 && it.code != 200) throw IOException("Upstream returned HTTP ${it.code}")
                val body = it.body.byteStream()
                // A 200 means the range was ignored and the body starts at byte 0.
                if (it.code == 200 && position > 0) body.skipFully(position)
                var remaining = chunkEnd - position + 1
                while (remaining > 0) {
                    val read = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                    position += read
                }
                if (remaining > 0) throw IOException("Upstream ended early at byte $position")
                retried = false
            }
        }
        output.flush()
    }

    private fun upstream(
        stream: ResolvedStream,
        start: Long,
        end: Long,
    ): Response {
        val request =
            Request
                .Builder()
                .url(stream.url)
                .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=$start-$end")
                .build()
        return http.newCall(request).execute()
    }

    private suspend fun stream(videoId: String): ResolvedStream? {
        streams[videoId]?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }?.let { return it }
        val data = resolve(videoId) ?: return null
        val length = data.format.contentLength?.takeIf { it > 0 } ?: probeLength(data) ?: return null
        val lifetimeMs = (data.streamExpiresInSeconds.coerceAtLeast(120) - 60) * 1000L
        return ResolvedStream(
            url = data.streamUrl,
            headers = data.streamHeaders,
            contentLength = length,
            expiresAt = SystemClock.elapsedRealtime() + lifetimeMs,
        ).also { streams[videoId] = it }
    }

    private fun probeLength(data: InnerTubeXPlayer.PlaybackData): Long? {
        val probe = ResolvedStream(data.streamUrl, data.streamHeaders, 0, 0)
        return runCatching {
            upstream(probe, 0, 0).use { response ->
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            }
        }.getOrNull()
    }

    companion object {
        private const val CHUNK_BYTES = 1L shl 20

        internal fun parseRange(
            header: String?,
            total: Long,
        ): Pair<Long, Long>? {
            if (header == null) return 0L to total - 1
            val spec = header.removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            val range =
                if (first.isEmpty()) {
                    val suffix = last.toLongOrNull() ?: return null
                    (total - suffix).coerceAtLeast(0) to total - 1
                } else {
                    val start = first.toLongOrNull() ?: return null
                    val end = last.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                    start to end
                }
            return range.takeIf { it.first in 0..it.second && it.first < total }
        }

        private fun localAddressFacing(host: String): String? =
            runCatching {
                DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName(host), SonosClient.PORT)
                    socket.localAddress?.hostAddress?.takeUnless { it == "0.0.0.0" || it.contains(':') }
                }
            }.getOrNull()

        private fun InputStream.readHeaderLine(): String? {
            val line = StringBuilder()
            while (true) {
                val byte = read()
                if (byte < 0) return if (line.isEmpty()) null else line.toString()
                if (byte == '\n'.code) return line.toString().trimEnd('\r')
                if (line.length > 8192) throw IOException("Header line too long")
                line.append(byte.toChar())
            }
        }

        private fun InputStream.skipFully(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped <= 0) {
                    if (read() < 0) throw IOException("Unexpected end of stream while skipping")
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }

        private fun OutputStream.writeStatus(
            code: Int,
            reason: String,
            vararg extraHeaders: String,
        ) {
            val head =
                buildString {
                    append("HTTP/1.1 $code $reason\r\n")
                    extraHeaders.forEach { append(it).append("\r\n") }
                    append("Content-Length: 0\r\nConnection: close\r\n\r\n")
                }
            write(head.toByteArray())
            flush()
        }
    }
}
