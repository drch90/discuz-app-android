package com.discuz.mobile

import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

data class DownloadedFile(val file: File, val mime: String)

/** Never delegates cookies to DownloadManager, whose redirects are outside our control. */
class SiteDownloader(private val policy: SitePolicy, private val userAgent: String) {
    fun fetch(url: String, imageOnly: Boolean, directory: File): DownloadedFile {
        var current = if (imageOnly) policy.resolveImageDownload(url) else policy.resolveDownload(url)
        var credentialsAllowed = policy.isForumUrl(current)
        for (redirect in 0..5) {
            require(imageOnly || policy.isForumUrl(current)) { "下载跳转到了站外地址" }
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Referer", policy.appUrl)
            if (credentialsAllowed && policy.isForumUrl(current)) CookieManager.getInstance().getCookie(current)?.let { connection.setRequestProperty("Cookie", it) }
            try {
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IllegalStateException("下载地址跳转无效")
                        val next = URI(current).resolve(location).toString()
                        if (imageOnly) {
                            policy.resolveImageDownload(next)
                            require(!(URI(current).scheme == "https" && URI(next).scheme == "http")) { "图片跳转降低了连接安全性" }
                        } else require(policy.isForumUrl(next)) { "下载跳转到了站外地址，请在浏览器中打开" }
                        credentialsAllowed = credentialsAllowed && policy.isForumUrl(next)
                        current = next
                        continue
                    }
                    200 -> Unit
                    401, 403 -> error("登录已失效或没有下载权限，请刷新后重试")
                    else -> error("文件下载失败（${connection.responseCode}）")
                }
                val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                    ?: "application/octet-stream"
                require(!imageOnly || mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                    "服务器未返回可保存的图片，请刷新后重试"
                }
                require(mime != "text/html") { "服务器返回了网页，请检查登录状态与下载权限" }
                val limit = if (imageOnly) 20L * 1024 * 1024 else 100L * 1024 * 1024
                require(connection.contentLengthLong <= limit) { "文件超过 ${limit / 1024 / 1024} MB，请使用浏览器下载" }
                val destination = File.createTempFile("discuz-download-", ".tmp", directory)
                try {
                    connection.inputStream.use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= limit) { "文件过大，请使用浏览器下载" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    return DownloadedFile(destination, mime)
                } catch (error: Exception) {
                    destination.delete()
                    throw error
                }
            } finally {
                connection.disconnect()
            }
        }
        error("下载地址跳转次数过多")
    }
}
