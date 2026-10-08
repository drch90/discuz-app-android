package com.discuz.mobile

import java.net.URI
import java.util.Locale

/** Shared URL rules for the WebView, native bridge and authenticated downloads. */
class SitePolicy private constructor(val forumUrl: String, val appUrl: String, val origin: String) {
    private val root = URI(forumUrl)
    private val rootPath = root.rawPath
    private val appPath = URI(appUrl).rawPath

    fun isOrigin(value: String): Boolean = runCatching {
        val uri = URI(value)
        validHttpUri(uri) && uri.scheme.equals(root.scheme, true)
            && uri.host.equals(root.host, true) && port(uri) == port(root)
    }.getOrDefault(false)

    fun isForumUrl(value: String?): Boolean = runCatching {
        val uri = URI(value ?: return false)
        isOrigin(value)
            && cleanPath(uri) && (uri.rawPath ?: "/").startsWith(rootPath)
    }.getOrDefault(false)

    fun isAppPage(value: String?): Boolean = isForumUrl(value) && runCatching {
        (URI(value).rawPath ?: "/").startsWith(appPath)
    }.getOrDefault(false)

    fun resolveDownload(value: String): String {
        val resolved = URI(forumUrl).resolve(value).toString()
        require(isForumUrl(resolved)) { "只能保存本站文件；外部文件请在浏览器中打开" }
        return resolved
    }

    fun resolveImageDownload(value: String): String {
        val resolved = URI(forumUrl).resolve(value).toString()
        require(isExternalWebUrl(resolved) && cleanPath(URI(resolved))) { "图片地址不正确" }
        return resolved
    }

    companion object {
        fun parse(input: String, allowHttp: Boolean): SitePolicy {
            val uri = runCatching { URI(input.trim()) }.getOrElse {
                throw IllegalArgumentException("请输入完整的 http:// 或 https:// 论坛地址")
            }
            require(validHttpUri(uri) && uri.query == null && uri.fragment == null && cleanPath(uri)) {
                "请输入完整论坛地址，不要包含账号、参数或页面锚点"
            }
            require(allowHttp || uri.scheme.equals("https", true)) { "正式版必须使用 HTTPS 地址" }
            val scheme = uri.scheme.lowercase(Locale.ROOT)
            val host = uri.host.lowercase(Locale.ROOT)
            val authority = if (uri.port == -1 || uri.port == if (scheme == "https") 443 else 80) host
                else "$host:${uri.port}"
            val origin = "$scheme://$authority"
            var path = (uri.rawPath ?: "/").trimEnd('/')
            if (path.endsWith("/app/index.html")) path = path.removeSuffix("/app/index.html")
            else if (path.endsWith("/app")) path = path.removeSuffix("/app")
            val forumUrl = "$origin$path/"
            return SitePolicy(forumUrl, "${forumUrl}app/", origin)
        }

        fun isExternalWebUrl(value: String): Boolean = runCatching {
            validHttpUri(URI(value))
        }.getOrDefault(false)

        private fun port(uri: URI): Int = if (uri.port != -1) uri.port
            else if (uri.scheme.equals("https", true)) 443 else 80

        private fun validHttpUri(uri: URI) = !uri.isOpaque && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")
            && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.port in -1..65535 && uri.port != 0

        private fun cleanPath(uri: URI): Boolean {
            val raw = uri.rawPath ?: "/"
            val path = uri.path ?: "/"
            return !raw.contains(Regex("%(2f|5c|25)", RegexOption.IGNORE_CASE))
                && !path.contains('\\') && path.split('/').none { it == "." || it == ".." }
                && path.none { it.code < 32 || it.code == 127 }
        }
    }
}
