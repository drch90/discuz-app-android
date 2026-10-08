package com.discuz.mobile

data class DomainManifest(val siteId: String, val domains: List<String>)
data class DomainProbeResult(val manifest: DomainManifest? = null, val disabled: Boolean = false)
data class DomainSelection(val site: SitePolicy, val result: DomainProbeResult)

/** Only admin-published endpoints or compiled seeds can become a WebView origin. */
class DomainDirectory(val seeds: List<SitePolicy>, private val allowHttp: Boolean) {
    var siteId: String? = null
        private set
    private var published = emptyList<SitePolicy>()
    val domains: List<SitePolicy> get() = published.ifEmpty { seeds }

    init { require(seeds.isNotEmpty() && seeds.size <= 8) }

    fun update(manifest: DomainManifest) {
        require(manifest.siteId.matches(Regex("[a-f0-9]{64}"))) { "站点标识不正确" }
        require(siteId == null || siteId == manifest.siteId) { "备用域名指向了不同的论坛" }
        require(manifest.domains.size <= 8) { "备用域名过多" }
        // Validate the whole update before changing trusted state.
        val next = manifest.domains.map { SitePolicy.parse(it, allowHttp) }.distinctBy { it.forumUrl }
        siteId = manifest.siteId
        published = next
    }

    fun snapshot(): DomainManifest? = siteId?.let { DomainManifest(it, published.map { site -> site.forumUrl }) }

    fun select(preferred: String?, excluded: Set<String>, probe: (SitePolicy) -> DomainProbeResult): DomainSelection {
        val ordered = domains.sortedBy { if (it.forumUrl == preferred) 0 else 1 }
        for (site in ordered) {
            if (site.forumUrl in excluded) continue
            val result = runCatching {
                val value = probe(site)
                if (!value.disabled) update(requireNotNull(value.manifest))
                value
            }.getOrNull() ?: continue
            // APP_DISABLED is a reachable, deliberately disabled service. Never
            // search other aliases to work around the administrator's switch.
            return DomainSelection(site, result)
        }
        throw IllegalStateException("所有连接线路暂时不可用，请检查网络后重试。")
    }
}
