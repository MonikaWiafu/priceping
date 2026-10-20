package io.github.priceping

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A hidden, full-size desktop browser. It loads a page exactly like Chrome would (running the
 * store's own scripts, cookies, lazy loading), then repeatedly runs [script] until [accept]
 * says the result is good enough or [timeoutMs] runs out. Main thread only.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebScraper(
    ctx: Context,
    private val url: String,
    private val script: String,
    private val timeoutMs: Long,
    private val accept: (String) -> Boolean,
    private val onDone: (String?) -> Unit,
) {
    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        const val HTML_SCRIPT = "document.documentElement ? document.documentElement.outerHTML : ''"
        private val main = Handler(Looper.getMainLooper())

        /** Turns the value evaluateJavascript hands back (a JSON literal) into a plain string. */
        fun unquote(v: String?): String? {
            if (v == null || v == "null" || v == "undefined") return null
            return try { JSONArray("[$v]").optString(0, null) } catch (e: Exception) { null }
        }

        /** Loads a page and returns its final HTML. Blocks; never call on the main thread. */
        fun loadHtmlBlocking(ctx: Context, url: String, timeoutMs: Long, accept: (String) -> Boolean): String? {
            if (Looper.myLooper() == Looper.getMainLooper()) return null
            val latch = CountDownLatch(1)
            var out: String? = null
            main.post {
                try {
                    WebScraper(ctx.applicationContext, url, HTML_SCRIPT, timeoutMs, accept) {
                        out = it
                        latch.countDown()
                    }.start()
                } catch (e: Throwable) {
                    latch.countDown() // WebView missing or disabled on this phone
                }
            }
            latch.await(timeoutMs + 8000, TimeUnit.MILLISECONDS)
            return out
        }
    }

    private val web = WebView(ctx)
    private var started = 0L
    private var finished = false
    private var pageLoaded = false
    private var best: String? = null
    private val poll = Runnable { tick() }
    private val kill = Runnable { finish(best) }

    init {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.userAgentString = UA
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.loadsImagesAutomatically = false
        s.blockNetworkImage = true
        s.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String) {
                pageLoaded = true
            }
        }
        // Give it a real desktop-sized viewport so stores render their full result lists.
        val w = View.MeasureSpec.makeMeasureSpec(1366, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        web.measure(w, h)
        web.layout(0, 0, 1366, 2400)
    }

    fun start() {
        started = System.currentTimeMillis()
        web.loadUrl(url)
        main.postDelayed(poll, 2500)
        main.postDelayed(kill, timeoutMs)
    }

    private fun tick() {
        if (finished) return
        web.evaluateJavascript(script) { raw ->
            if (finished) return@evaluateJavascript
            val v = unquote(raw)
            if (v != null && v.isNotEmpty()) {
                if (best == null || v.length > (best?.length ?: 0)) best = v
                if (pageLoaded && accept(v)) {
                    finish(v)
                    return@evaluateJavascript
                }
            }
            // Nudge lazy-loading lists, then look again.
            web.evaluateJavascript("try{window.scrollBy(0,1400)}catch(e){}", null)
            main.postDelayed(poll, 1500)
        }
    }

    private fun finish(result: String?) {
        if (finished) return
        finished = true
        main.removeCallbacks(poll)
        main.removeCallbacks(kill)
        try {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        } catch (e: Throwable) { }
        try { onDone(result) } catch (e: Throwable) { }
    }
}

/** Searches several stores at once, reporting each store's results as soon as they're in. */
object SearchEngine {
    class StoreDef(val key: String, val name: String, val url: (String) -> String)

    private fun enc(q: String) = URLEncoder.encode(q, "UTF-8")

    val STORES = listOf(
        StoreDef("amazon", "Amazon") { "https://www.amazon.com/s?k=${enc(it)}" },
        StoreDef("walmart", "Walmart") { "https://www.walmart.com/search?q=${enc(it)}" },
        StoreDef("bestbuy", "Best Buy") { "https://www.bestbuy.com/site/searchpage.jsp?st=${enc(it)}&intl=nosplash" },
        StoreDef("target", "Target") { "https://www.target.com/s?searchTerm=${enc(it)}" },
        StoreDef("ebay", "eBay") { "https://www.ebay.com/sch/i.html?_nkw=${enc(it)}" },
        StoreDef("newegg", "Newegg") { "https://www.newegg.com/p/pl?d=${enc(it)}" },
    )

    @Volatile private var script: String? = null

    private fun script(ctx: Context): String {
        script?.let { return it }
        val s = ctx.assets.open("extract.js").bufferedReader().use { it.readText() }
        script = s
        return s
    }

    /** Main thread only. [report] gets {store, name, status, items}. */
    fun run(ctx: Context, query: String, stores: Set<String>, report: (JSONObject) -> Unit) {
        val js = script(ctx)
        for (st in STORES) {
            if (st.key !in stores) continue
            val accept: (String) -> Boolean = { v ->
                try {
                    val o = JSONObject(v)
                    o.optBoolean("blocked") || (o.optJSONArray("items")?.length() ?: 0) >= 8
                } catch (e: Exception) { false }
            }
            try {
                WebScraper(ctx, st.url(query), js, 22_000, accept) { v ->
                    report(toReport(st, v))
                }.start()
            } catch (e: Throwable) {
                report(JSONObject().put("store", st.key).put("name", st.name).put("status", "error").put("items", JSONArray()))
            }
        }
    }

    private fun toReport(st: StoreDef, v: String?): JSONObject {
        val r = JSONObject().put("store", st.key).put("name", st.name)
        val o = try { if (v != null) JSONObject(v) else null } catch (e: Exception) { null }
        val items = o?.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            it.put("store", st.name)
            it.put("storeKey", st.key)
            it.put("rank", i)
            it.put("title", PriceParser.decode(it.optString("title")).trim())
        }
        r.put("items", items)
        r.put("status", when {
            items.length() > 0 -> "ok"
            o?.optBoolean("blocked") == true -> "blocked"
            o == null -> "error"
            else -> "empty"
        })
        return r
    }
}
