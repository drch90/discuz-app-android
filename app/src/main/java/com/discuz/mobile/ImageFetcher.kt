package com.discuz.mobile

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ImageHttpError(val status: Int) : IllegalStateException("图片加载失败（$status）")
data class ImageResponse(val stream: InputStream, val mime: String, val lifetime: Long, val length: Long = -1) : Closeable {
    override fun close() = stream.close()
}

/** Fetch only explicitly registered visible images. API, CAPTCHA and QR requests stay in WebView. */
class ImageFetcher(
    private val site: SitePolicy,
    private val userAgent: String,
    private val cookie: (String) -> String?,
    private val setCookie: (String, String) -> Unit,
) {
    companion object {
        val MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/avif", "image/bmp")
        private const val MAX_SIZE = 20L * 1024 * 1024
        fun lifetime(headers: Map<String, List<String>>, now: Long = System.currentTimeMillis()): Long {
            fun header(name: String) = headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }.joinToString(",")
            val control = header("Cache-Control").lowercase()
            if (control.split(',').any { it.trim().substringBefore('=') in setOf("no-store", "no-cache") }
                || header("Pragma").contains("no-cache", true)
                || header("Vary").split(',').any { it.trim().lowercase() !in setOf("", "accept-encoding", "cookie", "user-agent") }) return 0
            val age = header("Age").toLongOrNull()?.coerceAtLeast(0) ?: 0
            val maxAge = Regex("(?:^|,)\\s*max-age\\s*=\\s*\"?(\\d+)").find(control)?.groupValues?.get(1)?.toLongOrNull()
            fun date(value: String) = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            val dateAge = date(header("Date"))?.let { (now - it).coerceAtLeast(0) } ?: 0
            if (maxAge != null) return (minOf(maxAge, ImageDiskCache.MAX_AGE / 1000) * 1000 - maxOf(age.coerceAtMost(Long.MAX_VALUE / 1000) * 1000, dateAge)).coerceAtLeast(0)
            if (header("Expires").isNotBlank()) return minOf(ImageDiskCache.MAX_AGE, ((date(header("Expires")) ?: 0) - now).coerceAtLeast(0))
            return ImageDiskCache.MAX_AGE
        }
    }

    // Return after validating headers. WebView consumes the body as it arrives.
    fun open(url: String, public: Boolean, force: Boolean): ImageResponse {
        var current = site.resolveImageDownload(url)
        var credentials = !public && site.isForumUrl(current)
        var lifetime = ImageDiskCache.MAX_AGE
        for (redirect in 0..5) {
            require(!(site.origin.startsWith("https:") && current.startsWith("http:"))) { "图片需要安全连接" }
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Referer", site.appUrl)
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,image/*;q=0.8")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (force) { connection.setRequestProperty("Cache-Control", "no-cache"); connection.setRequestProperty("Pragma", "no-cache") }
            if (credentials) cookie(current)?.let { connection.setRequestProperty("Cookie", it) }
            var streaming = false
            try {
                val status = connection.responseCode
                val headers = connection.headerFields.filterKeys { it != null }
                if (credentials) headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.forEach { setCookie(current, it) }
                lifetime = minOf(lifetime, lifetime(headers))
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val next = site.resolveImageDownload(URI(current).resolve(connection.getHeaderField("Location") ?: error("图片跳转无效")).toString())
                    require(!(current.startsWith("https:") && next.startsWith("http:"))) { "图片跳转需要安全连接" }
                    credentials = credentials && site.isForumUrl(next)
                    current = next
                    continue
                }
                if (status != 200) throw ImageHttpError(status)
                val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                require(mime in MIME_TYPES) { "服务器没有返回图片" }
                val length = connection.contentLengthLong
                require(length <= MAX_SIZE && length != 0L) { "图片为空或超过 20 MB" }
                val input = connection.inputStream
                val body = object : InputStream() {
                    private var size = 0L
                    private var closed = false
                    override fun read(): Int {
                        val byte = ByteArray(1)
                        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                    }
                    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                        if (closed) throw IOException("图片流已关闭")
                        try {
                            val received = input.read(bytes, offset, count)
                            if (received < 0) {
                                if (size == 0L || (length >= 0 && size != length)) throw IOException("图片下载不完整")
                            } else {
                                size += received
                                if (size > MAX_SIZE || (length >= 0 && size > length)) throw IOException("图片超过大小限制")
                            }
                            return received
                        } catch (error: Exception) { close(); throw error }
                    }
                    override fun available() = if (closed) 0 else input.available()
                    override fun close() {
                        if (closed) return
                        closed = true
                        try { input.close() } finally { connection.disconnect() }
                    }
                }
                streaming = true
                return ImageResponse(body, mime, lifetime, length)
            } finally { if (!streaming) connection.disconnect() }
        }
        error("图片跳转次数过多")
    }
}
