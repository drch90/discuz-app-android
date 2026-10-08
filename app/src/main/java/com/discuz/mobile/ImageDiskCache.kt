package com.discuz.mobile

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/** Private app storage only. Image lifetime never grants permission to render a post. */
class ImageDiskCache(
    private val root: File,
    private val limit: Long = 200L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private var generation = 0L
    private val writes = mutableMapOf<String, Any>()

    init {
        root.mkdirs()
        root.listFiles()?.filter { it.name.startsWith("download-") }?.forEach { it.delete() }
        synchronized(this) { prune() }
    }

    /** Stream immediately; only a complete response may become a cache entry. */
    fun open(scope: String, url: String, force: Boolean, valid: () -> Boolean = { true }, download: () -> ImageResponse): ImageResponse {
        val key = hash(url) // Preserve every original signed query parameter.
        val writeKey = "$scope/$key"
        val token = Any()
        val folder = File(root, scope)
        val data = File(folder, "$key.image")
        val metadata = File(folder, "$key.properties")
        val epoch: Long
        synchronized(this) {
            if (!force) read(data, metadata)?.let { return guarded(it, valid) }
            data.delete(); metadata.delete()
            epoch = generation
            writes[writeKey] = token
        }
        val source = try { download() } catch (error: Exception) {
            synchronized(this) { if (writes[writeKey] === token) writes.remove(writeKey) }
            throw error
        }
        // Cache I/O is optional. A full/unavailable disk must not prevent display.
        var temp = if (source.lifetime > 0) runCatching {
            root.mkdirs()
            File.createTempFile("download-", ".tmp", root)
        }.getOrNull() else null
        var output = runCatching { temp?.outputStream()?.buffered() }.getOrNull()
        if (output == null) { temp?.delete(); temp = null }
        val body = object : InputStream() {
            private var size = 0L
            private var complete = false
            private var closed = false
            private fun discard() {
                runCatching { output?.close() }; output = null
                temp?.delete(); temp = null
            }
            private fun finish() {
                if (complete) return
                complete = true
                try {
                    output?.close(); output = null
                    synchronized(this@ImageDiskCache) {
                        val file = temp
                        if (file != null && size > 0 && size <= limit && generation == epoch && valid() && writes[writeKey] === token) {
                            folder.mkdirs()
                            check(file.renameTo(data))
                            val now = clock()
                            val props = Properties().apply {
                                setProperty("mime", source.mime)
                                setProperty("expires", (now + minOf(MAX_AGE, source.lifetime)).toString())
                                setProperty("stored", now.toString())
                            }
                            val pending = File(folder, "$key.pending")
                            pending.outputStream().use { props.store(it, null) }
                            check(pending.renameTo(metadata))
                            data.setLastModified(now)
                            prune(data)
                        }
                    }
                } catch (_: Exception) { /* A cache write failure does not fail the image. */ }
                finally {
                    discard()
                    synchronized(this@ImageDiskCache) { if (writes[writeKey] === token) writes.remove(writeKey) }
                }
            }
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (closed) throw IOException("图片流已关闭")
                try {
                    val received = source.stream.read(bytes, offset, count)
                    if (received < 0) {
                        if (size == 0L || (source.length >= 0 && size != source.length)) throw IOException("图片下载不完整")
                        finish()
                    } else if (received > 0) {
                        size += received
                        if (source.length >= 0 && size > source.length) throw IOException("图片长度不正确")
                        try { output?.write(bytes, offset, received) } catch (_: Exception) { discard() }
                        // WebView may close after Content-Length without a final EOF read.
                        if (source.length >= 0 && size == source.length) finish()
                    }
                    return received
                } catch (error: Exception) { close(); throw error }
            }
            override fun available() = if (closed) 0 else source.stream.available()
            override fun close() {
                if (closed) return
                closed = true
                try { source.close() } finally {
                    discard()
                    synchronized(this@ImageDiskCache) { if (writes[writeKey] === token) writes.remove(writeKey) }
                }
            }
        }
        return guarded(source.copy(stream = body), valid)
    }

    private fun guarded(source: ImageResponse, valid: () -> Boolean): ImageResponse {
        fun checkAccess() {
            if (!valid()) { source.close(); throw IOException("图片请求已取消") }
        }
        checkAccess()
        return source.copy(stream = object : InputStream() {
            override fun read(): Int {
                checkAccess(); val byte = source.stream.read(); checkAccess(); return byte
            }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                checkAccess(); val received = source.stream.read(bytes, offset, count); checkAccess(); return received
            }
            override fun available(): Int { checkAccess(); return source.stream.available() }
            override fun close() = source.close()
        })
    }

    private fun read(data: File, metadata: File): ImageResponse? = runCatching {
        if (!data.isFile || !metadata.isFile || data.length() == 0L) return null
        val props = Properties().apply { metadata.inputStream().use { load(it) } }
        val now = clock()
        val stored = props.getProperty("stored").toLong()
        if (now < stored || now >= props.getProperty("expires").toLong() || now - stored >= MAX_AGE) return null
        val mime = props.getProperty("mime")
        if (mime !in ImageFetcher.MIME_TYPES) return null
        data.setLastModified(now)
        ImageResponse(data.inputStream(), mime, props.getProperty("expires").toLong() - now, data.length())
    }.getOrNull()

    @Synchronized fun clear(): Long {
        generation++; writes.clear()
        val before = size()
        root.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
        return before
    }

    @Synchronized fun clearPrivate() {
        generation++; writes.clear()
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("private-") }?.forEach { it.deleteRecursively() }
    }

    @Synchronized fun size(): Long { prune(); return root.walkTopDown().filter { it.isFile && !it.name.startsWith("download-") }.sumOf { it.length() } }

    private fun prune(keep: File? = null) {
        val now = clock()
        val images = root.walkTopDown().filter { it.isFile && it.extension == "image" }.toList()
        val remaining = images.filter { file ->
            val meta = File(file.parentFile, "${file.nameWithoutExtension}.properties")
            val valid = runCatching {
                val props = Properties().apply { meta.inputStream().use { load(it) } }
                val stored = props.getProperty("stored").toLong()
                now >= stored && now - stored < MAX_AGE && now < props.getProperty("expires").toLong()
            }.getOrDefault(false)
            if (!valid) { file.delete(); meta.delete() }
            valid
        }.sortedBy { it.lastModified() }
        var bytes = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (bytes <= limit) break
            if (file == keep) continue
            bytes -= file.length(); file.delete()
            File(file.parentFile, "${file.nameWithoutExtension}.properties").delete()
        }
    }
}
