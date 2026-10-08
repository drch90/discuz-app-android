package com.discuz.mobile

import java.net.ServerSocket
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DirectImageDownloadTest {
    @Test fun imageCachePreservesHttpErrorsWithoutCachingOrRetrying() {
        val server = ServerSocket(0, 3, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10000
        val statuses = listOf(404, 410, 500)
        val worker = Thread {
            for (status in statuses) server.accept().use { socket ->
                socket.soTimeout = 10000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }.apply { isDaemon = true; start() }
        val directory = Files.createTempDirectory("image-errors").toFile()
        try {
            val root = "http://127.0.0.1:${server.localPort}/"
            val cache = ImageDiskCache(directory)
            val fetcher = ImageFetcher(SitePolicy.parse(root, true), "test", { null }, { _, _ -> })
            for (status in statuses) {
                val error = assertThrows(ImageHttpError::class.java) {
                    cache.open("private-user", root + status, false) { fetcher.open(root + status, false, false) }
                }
                assertEquals(status, error.status)
                assertEquals(0L, cache.size())
            }
            worker.join(10000); assertFalse(worker.isAlive)
        } finally { server.close(); worker.join(10000); directory.deleteRecursively() }
    }

    @Test fun externalImageRedirectDoesNotAcquireForumCookies() {
        val server = ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10000
        val cookies = mutableListOf<String?>()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val worker = Thread {
            repeat(2) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.add(line)
                    }
                    cookies.add(headers.firstOrNull { it.startsWith("Cookie:", true) })
                    val header = if (index == 0)
                        "HTTP/1.1 302 Found\r\nLocation: /forum/photo.png\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else
                        "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(header.toByteArray())
                    if (index == 1) socket.getOutputStream().write(bytes)
                }
            }
        }.apply { isDaemon = true; start() }
        val directory = Files.createTempDirectory("image-test").toFile()
        try {
            val root = "http://127.0.0.1:${server.localPort}"
            val policy = SitePolicy.parse("$root/forum/", true)
            val result = SiteDownloader(policy, "test").fetch("$root/storage/photo", true, directory)
            worker.join(10000)
            assertFalse(worker.isAlive)
            assertArrayEquals(bytes, result.file.readBytes())
            assertEquals(listOf<String?>(null, null), cookies)
            assertThrows(IllegalArgumentException::class.java) {
                SiteDownloader(policy, "test").fetch("$root/storage/photo", false, directory)
            }
        } finally { server.close(); worker.join(10000); directory.deleteRecursively() }
    }
}
