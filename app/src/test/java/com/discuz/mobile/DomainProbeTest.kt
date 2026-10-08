package com.discuz.mobile

import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DomainProbeTest {
    private fun server(status: String, type: String, body: String, extra: String = "", test: (SitePolicy, AtomicReference<String>) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val request = AtomicReference("")
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                    request.set(lines.joinToString("\n"))
                    val payload = body.toByteArray()
                    val headers = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n$extra\r\n"
                    socket.getOutputStream().write(headers.toByteArray() + payload)
                }
            } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        try { test(SitePolicy.parse("http://127.0.0.1:${server.localPort}/", true), request) }
        finally { server.close(); worker.join(6000) }
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test fun metadataUsesOnlyGetWithoutCookiesOrAuthorization() {
        val body = """{"code":"OK","data":{"protocol":1,"site_id":"${"a".repeat(64)}","domains":["https://backup.example/"]}}"""
        server("200 OK", "application/json; charset=UTF-8", body) { site, received ->
            assertEquals(listOf("https://backup.example/"), DomainProbe().fetch(site).manifest!!.domains)
            assertTrue(received.get().startsWith("GET /api/app/?action=site.domains&v=1 HTTP/1.1"))
            assertFalse(received.get().contains("Cookie:", true))
            assertFalse(received.get().contains("Authorization:", true))
        }
    }

    @Test fun redirectsAreRejectedBeforeOpeningTheirDestination() {
        server("302 Found", "text/html", "", "Location: http://127.0.0.1:1/elsewhere\r\n") { site, _ ->
            assertThrows(IllegalArgumentException::class.java) { DomainProbe().fetch(site) }
        }
    }

    @Test fun disabledResponseIsReachableButHtmlAndOversizedBodiesAreNot() {
        server("503 Unavailable", "application/json", "{\"code\":\"APP_DISABLED\"}") { site, _ ->
            assertTrue(DomainProbe().fetch(site).disabled)
        }
        server("200 OK", "text/html", "<html>error</html>") { site, _ ->
            assertThrows(IllegalArgumentException::class.java) { DomainProbe().fetch(site) }
        }
        server("200 OK", "application/json", "x".repeat(32769)) { site, _ ->
            assertThrows(IllegalArgumentException::class.java) { DomainProbe().fetch(site) }
        }
    }
}
