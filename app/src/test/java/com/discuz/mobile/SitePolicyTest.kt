package com.discuz.mobile

import org.junit.Assert.*
import org.junit.Test

class SitePolicyTest {
    @Test fun acceptsForumSubdirectoriesAndAvoidsDuplicateAppSuffix() {
        for (url in listOf("http://192.168.1.10:8080/forum", "http://192.168.1.10:8080/forum/", "http://192.168.1.10:8080/forum/app/", "http://192.168.1.10:8080/forum/app/index.html")) {
            val policy = SitePolicy.parse(url, true)
            assertEquals("http://192.168.1.10:8080/forum/app/", policy.appUrl)
            assertEquals("http://192.168.1.10:8080", policy.origin)
        }
    }

    @Test fun acceptsHttpsInDebugAndRelease() {
        assertEquals("https://example.com/app/", SitePolicy.parse("https://EXAMPLE.com:443/", true).appUrl)
        assertEquals("https://example.com/app/", SitePolicy.parse("https://example.com", false).appUrl)
        assertEquals("http://[::1]:8080/forum/app/", SitePolicy.parse("http://[::1]:8080/forum/", true).appUrl)
    }

    @Test fun rejectsReleaseHttpAndDangerousConfiguration() {
        for (url in listOf("http://example.com/", "https://user:pass@example.com/", "javascript:alert(1)", "file:///app/index.html", "https://example.com/forum/../", "https://example.com/forum/%2e%2e/", "https://example.com/forum/%252e%252e/", "https://example.com/?url=other", "https://example.com/#/home")) {
            assertThrows(IllegalArgumentException::class.java) { SitePolicy.parse(url, false) }
        }
    }

    @Test fun sessionBoundariesCoverOriginAndForumPath() {
        val policy = SitePolicy.parse("https://example.com/forum/", false)
        assertTrue(policy.isOrigin("https://example.com/"))
        assertFalse(policy.isOrigin("http://example.com/"))
        assertTrue(policy.isForumUrl("https://example.com/forum/forum.php?mod=attachment&aid=123"))
        assertTrue(policy.isAppPage("https://example.com/forum/app/#/thread/123"))
        assertFalse(policy.isAppPage("https://example.com/forum/plugin.php"))
        for (url in listOf("https://example.com.evil/forum/file", "https://example.com/other/file", "https://example.com/forum2/file", "http://example.com/forum/file", "https://example.com:8443/forum/file", "https://example.com/forum/../secret", "https://example.com/forum/%2e%2e/secret", "https://example.com/forum/%2f..%2fsecret", "https://example.com/forum/%255c..", "https://example.com@evil.test/forum/file")) {
            assertFalse(url, policy.isForumUrl(url))
            assertThrows(IllegalArgumentException::class.java) { policy.resolveDownload(url) }
        }
    }

    @Test fun resolvesOnlyTrustedDownloads() {
        val policy = SitePolicy.parse("http://192.168.1.10/discuz", true)
        assertEquals("http://192.168.1.10/discuz/data/image.png", policy.resolveDownload("data/image.png"))
        assertThrows(IllegalArgumentException::class.java) { policy.resolveDownload("../private") }
        assertThrows(IllegalArgumentException::class.java) { policy.resolveDownload("//evil.test/a.png") }
    }
    @Test fun directImageDownloadsAllowStorageHostsWithoutRelaxingFileRules() {
        val policy = SitePolicy.parse("https://example.com/forum/", false)
        val image = "https://images.example.net/forum/photo.png"
        assertEquals(image, policy.resolveImageDownload(image))
        assertThrows(IllegalArgumentException::class.java) { policy.resolveDownload(image) }
        for (url in listOf("file:///secret", "javascript:alert(1)", "https://user:pass@example.net/a.png", "https://example.net/%2e%2e/private")) {
            assertThrows(IllegalArgumentException::class.java) { policy.resolveImageDownload(url) }
        }
    }
}
