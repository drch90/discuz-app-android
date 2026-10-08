package com.discuz.mobile

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/** Public metadata only: no WebView Cookie, authorization, request payload or redirects. */
class DomainProbe {
    fun fetch(site: SitePolicy): DomainProbeResult {
        val connection = URL(site.forumUrl + "api/app/?action=site.domains&v=1").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            require(status == 200 || status == 503) { "线路未就绪" }
            require(connection.contentType.orEmpty().substringBefore(';').trim() == "application/json")
            val end = System.nanoTime() + 3_000_000_000L
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val bytes = ByteArrayOutputStream()
            requireNotNull(stream).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    check(System.nanoTime() <= end) { "线路检测超时" }
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(bytes.size() + count <= 32768) { "线路配置过大" }
                    bytes.write(buffer, 0, count)
                }
            }
            val response = JSONObject(bytes.toString("UTF-8"))
            if (status == 503 && response.optString("code") == "APP_DISABLED") return DomainProbeResult(disabled = true)
            require(status == 200 && response.optString("code") == "OK")
            val data = response.getJSONObject("data")
            require(data.getInt("protocol") == 1)
            val urls = data.getJSONArray("domains")
            require(urls.length() <= 8)
            return DomainProbeResult(DomainManifest(data.getString("site_id"), (0 until urls.length()).map { urls.getString(it) }))
        } finally { connection.disconnect() }
    }
}
