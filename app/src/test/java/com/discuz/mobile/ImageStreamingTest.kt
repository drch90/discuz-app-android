package com.discuz.mobile

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ImageStreamingTest {
    private fun response(text: String, length: Long = text.length.toLong()) =
        ImageResponse(text.byteInputStream(), "image/png", ImageDiskCache.MAX_AGE, length)

    @Test fun firstBytesReachReaderBeforeNetworkCompletesAndOnlyFullBodyIsCached() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val root = Files.createTempDirectory("image-stream").toFile()
        val worker = Thread {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 8\r\nCache-Control: max-age=60\r\nConnection: close\r\n\r\npart".toByteArray())
                output.flush()
                check(finish.await(5, TimeUnit.SECONDS))
                output.write("done".toByteArray()); output.flush()
            }
        }.apply { isDaemon = true; start() }
        try {
            val origin = "http://127.0.0.1:${server.localPort}/"
            val cache = ImageDiskCache(root)
            val fetch = ImageFetcher(SitePolicy.parse(origin, true), "test", { null }, { _, _ -> })
            val pending = executor.submit<ImageResponse> { cache.open("private-user", origin, false) { fetch.open(origin, false, false) } }
            pending.get(2, TimeUnit.SECONDS).use { image ->
                assertEquals(8L, image.length)
                val first = executor.submit<String> { String(image.stream.readNBytes(4)) }
                assertEquals("part", first.get(2, TimeUnit.SECONDS))
                assertFalse(root.walkTopDown().any { it.extension == "image" })
                finish.countDown()
                assertEquals("done", String(image.stream.readNBytes(4)))
                // The exact known length commits even if the consumer closes without reading EOF.
            }
            cache.open("private-user", origin, false) { error("cache miss") }.use {
                assertEquals("partdone", String(it.stream.readBytes()))
            }
        } finally { finish.countDown(); executor.shutdownNow(); server.close(); worker.join(5000); root.deleteRecursively() }
    }

    @Test fun partialCloseTruncationAndFailedReadsNeverPopulateCache() {
        val root = Files.createTempDirectory("image-partial").toFile()
        try {
            val cache = ImageDiskCache(root)
            cache.open("private-user", "partial", false) { response("abcdef") }.use { assertEquals('a'.code, it.stream.read()) }
            assertEquals(0L, cache.size())
            assertThrows(IOException::class.java) {
                cache.open("private-user", "truncated", false) { response("abc", 6) }.use { it.stream.readBytes() }
            }
            assertEquals(0L, cache.size())
            cache.open("private-user", "empty", false) { response("", -1) }.use {
                assertThrows(IOException::class.java) { it.stream.read() }
            }
            assertFalse(root.walkTopDown().any { it.name.startsWith("download-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun accountInvalidationStopsActiveAndCachedStreamsAndClearingPreventsWrites() {
        val root = Files.createTempDirectory("image-access").toFile()
        try {
            val cache = ImageDiskCache(root)
            var allowed = true
            cache.open("private-user", "url", false, valid = { allowed }) { response("abcdef") }.use {
                assertEquals('a'.code, it.stream.read())
                allowed = false
                assertThrows(IOException::class.java) { it.stream.read() }
            }
            assertEquals(0L, cache.size())
            allowed = true
            cache.open("private-user", "url", false) { response("abcdef") }.use { it.stream.readBytes() }
            cache.open("private-user", "url", false, valid = { allowed }) { error("cache miss") }.use {
                allowed = false
                assertThrows(IOException::class.java) { it.stream.read() }
            }
            cache.clear()
            cache.open("private-user", "url", false) { response("abcdef") }.use {
                assertEquals('a'.code, it.stream.read()); cache.clear(); it.stream.readBytes()
            }
            assertEquals(0L, cache.size())
        } finally { root.deleteRecursively() }
    }

    @Test fun olderDownloadCannotOverwriteForcedRefresh() {
        val root = Files.createTempDirectory("image-race").toFile()
        try {
            val cache = ImageDiskCache(root)
            cache.open("private-user", "url", false) { response("old") }.use { old ->
                cache.open("private-user", "url", true) { response("new") }.use { it.stream.readBytes() }
                old.stream.readBytes()
            }
            cache.open("private-user", "url", false) { error("cache miss") }.use { assertEquals("new", String(it.stream.readBytes())) }
        } finally { root.deleteRecursively() }
    }

    @Test fun httpTruncatedBodyIsAnErrorAndNotCached() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val root = Files.createTempDirectory("image-short-http").toFile()
        val worker = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 8\r\nConnection: close\r\n\r\npart".toByteArray())
            }
        }.apply { isDaemon = true; start() }
        try {
            val origin = "http://127.0.0.1:${server.localPort}/"
            val fetch = ImageFetcher(SitePolicy.parse(origin, true), "test", { null }, { _, _ -> })
            val cache = ImageDiskCache(root)
            assertThrows(IOException::class.java) { cache.open("private-user", origin, false) { fetch.open(origin, false, false) }.use { it.stream.readBytes() } }
            assertEquals(0L, cache.size())
        } finally { server.close(); worker.join(5000); root.deleteRecursively() }
    }

    @Test fun unknownLengthOversizeResponseStopsAndDiscardsPartialCache() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val root = Files.createTempDirectory("image-limit").toFile()
        val worker = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nConnection: close\r\n\r\n".toByteArray())
                runCatching { repeat(336) { output.write(ByteArray(64 * 1024)) } }
            }
        }.apply { isDaemon = true; start() }
        try {
            val origin = "http://127.0.0.1:${server.localPort}/"
            val fetch = ImageFetcher(SitePolicy.parse(origin, true), "test", { null }, { _, _ -> })
            val cache = ImageDiskCache(root)
            assertThrows(IOException::class.java) {
                cache.open("private-user", origin, false) { fetch.open(origin, false, false) }.use {
                    val buffer = ByteArray(64 * 1024)
                    while (it.stream.read(buffer) >= 0) { }
                }
            }
            assertEquals(0L, cache.size())
            assertFalse(root.walkTopDown().any { it.name.startsWith("download-") })
        } finally { server.close(); worker.join(5000); root.deleteRecursively() }
    }

    @Test fun unavailableCacheDiskDoesNotBlockImageDisplay() {
        val root = Files.createTempDirectory("image-no-disk").toFile()
        try {
            val cache = ImageDiskCache(root)
            root.deleteRecursively(); root.writeText("not a directory")
            cache.open("private-user", "url", false) { response("image") }.use {
                assertEquals("image", String(it.stream.readBytes()))
            }
        } finally { root.deleteRecursively() }
    }
}
