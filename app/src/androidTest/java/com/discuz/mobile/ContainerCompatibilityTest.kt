package com.discuz.mobile

import android.app.UiModeManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Tests only the native container against a new, minimal fixture, not a private website. */
@RunWith(AndroidJUnit4::class)
class ContainerCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var fixture: LocalFixture
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalNightMode = UiModeManager.MODE_NIGHT_AUTO

    @Before fun prepare() {
        originalNightMode = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).nightMode
        setNight(false)
        fixture = LocalFixture()
        context.getSharedPreferences("site", Context.MODE_PRIVATE).edit().clear()
            .putString("siteUrl", fixture.origin).putString("appearanceMode", "system").commit()
    }

    @After fun cleanup() {
        scenario?.close()
        if (::fixture.isInitialized) fixture.close()
        shell("cmd uimode night " + when (originalNightMode) {
            UiModeManager.MODE_NIGHT_YES -> "yes"
            UiModeManager.MODE_NIGHT_NO -> "no"
            else -> "auto"
        })
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun setNight(dark: Boolean) = shell("cmd uimode night ${if (dark) "yes" else "no"}")
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun web(): WebView {
        val result = AtomicReference<WebView>()
        scenario!!.onActivity { result.set(findWeb(it.window.decorView)) }
        return checkNotNull(result.get()) { "Native WebView not ready" }
    }
    private fun evaluate(script: String): String {
        val result = AtomicReference<String>()
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync { webOnMain().evaluateJavascript(script) { result.set(it); ready.countDown() } }
        check(ready.await(5, TimeUnit.SECONDS)) { "JavaScript did not complete" }
        return result.get()
    }
    private fun webOnMain(): WebView = checkNotNull(currentWeb.get())
    private val currentWeb = AtomicReference<WebView>()
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) {
            if (runCatching(predicate).getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label")
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("App fixture and native bridge") {
            currentWeb.set(web())
            evaluate("!!window.info && typeof window.systemDark === 'boolean'") == "true"
        }
    }
    private fun darkSurface(): Boolean {
        var dark = false
        scenario!!.onActivity { activity ->
            val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
            dark = (root.background as ColorDrawable).color == Color.BLACK &&
                !WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars
        }
        return dark
    }

    @Test fun launchesAndReportsNativeVersion() {
        launch()
        assertEquals(BuildConfig.VERSION_CODE.toString(), evaluate("window.info.versionCode"))
        assertEquals("\"debug\"", evaluate("window.info.buildType"))
    }

    @Test fun systemThemeChangesPreserveWebViewAndDraft() {
        launch()
        await("initial system light") { evaluate("window.systemDark") == "false" && !darkSurface() }
        val originalWeb = web()
        evaluate("document.querySelector('textarea').value='keep draft'")
        setNight(true)
        await("system dark event and bars") { evaluate("window.systemDark") == "true" && darkSurface() }
        assertSame("Theme changes must not recreate the WebView", originalWeb, web())
        assertEquals("\"keep draft\"", evaluate("document.querySelector('textarea').value"))
        setNight(false)
        await("system light event and bars") { evaluate("window.systemDark") == "false" && !darkSurface() }
        assertSame(originalWeb, web())
    }

    @Test fun manualChoiceWinsAndReturningToSystemUsesAndroidState() {
        launch()
        evaluate("window.appearance('dark','dark')")
        await("manual dark") { darkSurface() }
        setNight(true)
        await("system changed while manual dark") { evaluate("window.systemDark") == "true" }
        setNight(false)
        await("manual dark retained in system light") { evaluate("window.systemDark") == "false" && darkSurface() }
        // Deliberately stale web theme: system mode must use native uiMode.
        evaluate("window.appearance('system','dark')")
        await("native system state takes precedence") { !darkSurface() }
        evaluate("window.appearance('light','light')")
        setNight(true)
        await("manual light retained in system dark") { evaluate("window.systemDark") == "true" && !darkSurface() }
    }
}

private class LocalFixture : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val origin = "http://127.0.0.1:${server.localPort}/"
    init {
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute {
                    runCatching { socket.use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val api = path.startsWith("/api/app/")
                        val body = if (api) JSONObject().put("code", "OK").put("data", JSONObject()
                            .put("protocol", 1).put("site_id", "a".repeat(64))
                            .put("domains", org.json.JSONArray().put(origin))).toString() else PAGE
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: ${if (api) "application/json" else "text/html"}; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    } }
                }
            }
        }
    }
    override fun close() { server.close(); workers.shutdownNow() }
    companion object {
        // Independent test page: no Vue source, forum templates, backend or real configuration.
        private val PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Android container fixture</title><textarea aria-label="Draft"></textarea><script>
            window.addEventListener('discuz:system-theme',function(event){window.systemDark=event.detail.dark;});
            window.appearance=function(mode,theme){DiscuzNative.postMessage(JSON.stringify({id:'theme',action:'appearance',mode:mode,theme:theme}));};
            DiscuzNative.onmessage=function(event){var reply=JSON.parse(event.data);if(reply.id==='info'&&reply.ok)window.info=reply.data;};
            DiscuzNative.postMessage(JSON.stringify({id:'info',action:'appInfo'}));
            appearance('system','light');
            </script>""".trimIndent()
    }
}
