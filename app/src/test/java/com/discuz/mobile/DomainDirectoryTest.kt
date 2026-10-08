package com.discuz.mobile

import org.junit.Assert.*
import org.junit.Test

class DomainDirectoryTest {
    private val id = "a".repeat(64)
    private val first = SitePolicy.parse("https://first.example/forum/", false)
    private val second = SitePolicy.parse("https://second.example/", false)
    private fun directory() = DomainDirectory(listOf(first, second), false)
    private fun healthy(vararg urls: String) = DomainProbeResult(DomainManifest(id, urls.toList()))

    @Test fun unavailablePrimaryFallsBackAndPrefersLastWorkingDomain() {
        val directory = directory()
        val calls = mutableListOf<String>()
        val selected = directory.select(null, emptySet()) {
            calls.add(it.forumUrl)
            if (it.forumUrl == first.forumUrl) error("offline")
            healthy()
        }
        assertEquals(second.forumUrl, selected.site.forumUrl)
        assertEquals(listOf(first.forumUrl, second.forumUrl), calls)
        calls.clear()
        directory.select(second.forumUrl, emptySet()) { calls.add(it.forumUrl); healthy() }
        assertEquals(listOf(second.forumUrl), calls)
    }

    @Test fun adminListReplacesOldDomainsAndSurvivesRestart() {
        val directory = directory()
        directory.update(DomainManifest(id, listOf("https://new.example/forum/")))
        val restarted = directory()
        restarted.update(directory.snapshot()!!)
        assertEquals(listOf("https://new.example/forum/"), restarted.domains.map { it.forumUrl })
        assertThrows(IllegalStateException::class.java) {
            restarted.select(first.forumUrl, setOf("https://new.example/forum/")) { fail("Removed seeds must not be tried"); healthy() }
        }
        restarted.update(DomainManifest(id, emptyList()))
        assertEquals(listOf(first, second), restarted.domains)
    }

    @Test fun invalidUpdateKeepsKnownGoodListAndSiteIdentity() {
        val directory = directory()
        directory.update(DomainManifest(id, listOf(second.forumUrl)))
        for (manifest in listOf(DomainManifest("b".repeat(64), listOf(first.forumUrl)),
            DomainManifest(id, listOf(first.forumUrl, "http://insecure.example/")),
            DomainManifest(id, List(9) { first.forumUrl }), DomainManifest("bad", emptyList()))) {
            assertThrows(IllegalArgumentException::class.java) { directory.update(manifest) }
            assertEquals(listOf(second.forumUrl), directory.snapshot()!!.domains)
            assertEquals(id, directory.siteId)
        }
    }

    @Test fun wrongForumCannotBeSelectedAndDisabledServiceStopsFailover() {
        val directory = directory()
        directory.update(DomainManifest(id, emptyList()))
        val selected = directory.select(null, emptySet()) {
            if (it.forumUrl == first.forumUrl) DomainProbeResult(DomainManifest("b".repeat(64), emptyList())) else healthy()
        }
        assertEquals(second.forumUrl, selected.site.forumUrl)
        var calls = 0
        val disabled = directory.select(null, emptySet()) { calls++; DomainProbeResult(disabled = true) }
        assertTrue(disabled.result.disabled)
        assertEquals(1, calls)
    }

    @Test fun failedDocumentsDoNotLoopAndDebugCanUseHttp() {
        val directory = directory()
        assertEquals(second.forumUrl, directory.select(first.forumUrl, setOf(first.forumUrl)) { healthy() }.site.forumUrl)
        assertThrows(IllegalStateException::class.java) { directory.select(null, setOf(first.forumUrl, second.forumUrl)) { healthy() } }
        val debug = DomainDirectory(listOf(first), true)
        debug.update(DomainManifest(id, listOf("http://127.0.0.1:18088/")))
        assertEquals("http://127.0.0.1:18088/", debug.domains.single().forumUrl)
    }
}
