package com.discuz.mobile

import java.net.ServerSocket
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ImageCacheTest {
    private fun body(image: ImageResponse) = image.use { String(it.stream.readBytes()) }
    private fun response(text: String, age: Long = ImageDiskCache.MAX_AGE) = ImageResponse(text.byteInputStream(), "image/png", age, text.toByteArray().size.toLong())

    @Test fun cachedImagesExpireForceReloadAndKeepSignedQueriesSeparate() {
        val root = Files.createTempDirectory("images-test").toFile()
        try {
            var now = 1_800_000_000_000L; var calls = 0
            val cache = ImageDiskCache(root, clock = { now })
            fun load(url: String = "https://site/image?signature=a", force: Boolean = false) = cache.open("private-user1", url, force) {
                calls++; response("image-$calls", ImageDiskCache.MAX_AGE * 2)
            }
            assertEquals("image-1", body(load()))
            now += ImageDiskCache.MAX_AGE - 1
            assertEquals("image-1", body(load())); assertEquals(1, calls)
            now++
            assertEquals("image-2", body(load()))
            assertEquals("image-3", body(load(force = true)))
            assertEquals("image-4", body(load("https://site/image?signature=b")))
            assertEquals("image-3", body(load()))
        } finally { root.deleteRecursively() }
    }

    @Test fun switchingUsersClearsPrivateButRetainsPublicCoversAndNoStoreIsNeverSaved() {
        val root = Files.createTempDirectory("images-test").toFile()
        try {
            val cache = ImageDiskCache(root); var calls = 0
            fun load(scope: String, age: Long = ImageDiskCache.MAX_AGE) = cache.open(scope, "same-url", false) {
                calls++; response("image-$calls", age)
            }
            assertEquals("image-1", body(load("private-1")))
            assertEquals("image-2", body(load("private-2")))
            assertEquals("image-3", body(load("public-site")))
            cache.clearPrivate()
            assertEquals("image-3", body(load("public-site")))
            assertEquals("image-4", body(load("private-1")))
            val temporary = load("private-nostore", 0)
            body(temporary); assertFalse(root.walkTopDown().any { it.name.startsWith("download-") })
            assertEquals("image-6", body(load("private-nostore", 0)))
            cache.clear(); assertEquals(0, cache.size())
        } finally { root.deleteRecursively() }
    }

    @Test fun clearingDuringDownloadPreventsRepopulationAndCorruptMetadataIsAMiss() {
        val root = Files.createTempDirectory("images-test").toFile()
        try {
            val cache = ImageDiskCache(root)
            val image = cache.open("private-user", "url", false) {
                cache.clear(); response("old request")
            }
            assertEquals("old request", body(image)); assertEquals(0, cache.size())
            body(cache.open("private-user", "url", false) { response("ok") })
            root.walkTopDown().first { it.extension == "properties" }.writeText("broken metadata")
            val recovered = cache.open("private-user", "url", false) { response("fresh") }
            assertEquals("fresh", body(recovered))
            root.deleteRecursively()
            val afterReclaim = cache.open("private-user", "url", false) { response("recreated") }
            assertEquals("recreated", body(afterReclaim))
        } finally { root.deleteRecursively() }
    }

    @Test fun evictsLeastRecentlyUsedImagesWhenOverCapacity() {
        val root = Files.createTempDirectory("images-test").toFile()
        try {
            var now = 1_800_000_000_000L; var calls = 0
            val cache = ImageDiskCache(root, limit = 8, clock = { now })
            fun load(url: String) = cache.open("public-site", url, false) { calls++; response("1234") }
            body(load("one")); now++; body(load("two")); now++; body(load("one")); now++; body(load("three"))
            assertEquals(3, calls); body(load("one")); assertEquals(3, calls)
            body(load("two")); assertEquals(4, calls)
        } finally { root.deleteRecursively() }
    }

    @Test fun obeysServerCacheConstraints() {
        fun age(vararg headers: Pair<String, String>) = ImageFetcher.lifetime(headers.associate { it.first to listOf(it.second) }, 1_800_000_000_000L)
        assertEquals(0, age("Cache-Control" to "private, no-store"))
        assertEquals(0, age("Cache-Control" to "no-cache"))
        assertEquals(0, age("Vary" to "*"))
        assertEquals(0, age("Vary" to "Authorization"))
        assertEquals(0, age("Expires" to "-1"))
        assertEquals(50_000, age("Cache-Control" to "private, max-age=60", "Age" to "10"))
        assertEquals(ImageDiskCache.MAX_AGE, age("Cache-Control" to "max-age=31536000"))
        assertEquals(ImageDiskCache.MAX_AGE, age())
    }

    @Test fun fetchPreservesCookiesOnlyForForumAndForcesNetworkWithoutForwardingCredentials() {
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10000
        val received = mutableListOf<Triple<String, String?, String?>>()
        val origin = "http://127.0.0.1:${server.localPort}"
        val worker = Thread {
            repeat(6) {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    received.add(Triple(path, headers["cookie"], headers["cache-control"]))
                    val response = when (path.substringBefore('?')) {
                        "/forum/redirect" -> "302 Found\r\nLocation: $origin/outside/back\r\nContent-Length: 0"
                        "/outside/back" -> "302 Found\r\nLocation: $origin/forum/final\r\nContent-Length: 0"
                        "/forum/denied" -> "403 Forbidden\r\nContent-Length: 0"
                        else -> "200 OK\r\nContent-Type: image/png\r\nCache-Control: no-store\r\nContent-Length: 5"
                    }
                    socket.getOutputStream().write(("HTTP/1.1 $response\r\nConnection: close\r\n\r\n" + if (response.startsWith("200")) "image" else "").toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val fetch = ImageFetcher(SitePolicy.parse("$origin/forum/", true), "test", { "session=secret" }, { _, _ -> })
            fetch.open("$origin/forum/photo?signature=original", false, true).use { assertEquals(0, it.lifetime); assertEquals("image", body(it)) }
            assertEquals(Triple("/forum/photo?signature=original", "session=secret", "no-cache"), received.last())
            body(fetch.open("$origin/forum/cover", true, false)); assertNull(received.last().second)
            body(fetch.open("$origin/forum/redirect", false, false))
            assertEquals("session=secret", received[2].second); assertNull(received[3].second); assertNull(received[4].second)
            assertThrows(IllegalStateException::class.java) { fetch.open("$origin/forum/denied", false, false) }
        } finally { server.close(); worker.join(10000) }
    }
}
