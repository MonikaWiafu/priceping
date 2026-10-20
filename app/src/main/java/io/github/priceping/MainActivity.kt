package io.github.priceping

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.Executors

/** Hosts the app's screens (assets/index.html) and bridges them to the native price checker. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newCachedThreadPool()
    private val lock = Any()
    private var pending = JSONObject()
    @Volatile private var pageReady = false
    @Volatile private var destroyed = false

    private val refresh = Runnable { push(JSONObject().put("type", "state")) }
    private val storeListener: () -> Unit = {
        ui.removeCallbacks(refresh)
        ui.postDelayed(refresh, 150)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        Scheduler.apply(this)

        web = WebView(this)
        web.setBackgroundColor(if (isDark()) 0xFF111416.toInt() else 0xFFF6F7F9.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.startsWith("file:///android_asset/")) return false
                openExternal(u)
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)

        handleIntent(intent)
        Store.onChange = storeListener
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        if (pageReady) flushPending()
    }

    override fun onResume() {
        super.onResume()
        Store.onChange = storeListener
        if (pageReady) push(JSONObject().put("type", "state"))
    }

    override fun onDestroy() {
        destroyed = true
        if (Store.onChange === storeListener) Store.onChange = null
        exec.shutdown()
        web.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!pageReady) {
            finish()
            return
        }
        web.evaluateJavascript("(window.goBack && window.goBack()) ? 'y' : 'n'") { r ->
            if (r == null || !r.contains("y")) finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        push(JSONObject().put("type", "state"))
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        synchronized(lock) {
            if (i.action == Intent.ACTION_SEND) {
                val text = (i.getStringExtra(Intent.EXTRA_TEXT) ?: "") + " " + (i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
                val url = Checker.extractUrl(text)
                if (url != null) pending.put("sharedUrl", url) else pending.put("toast", "No link found in what was shared.")
            }
            i.getStringExtra("openItem")?.let { pending.put("openItem", it) }
        }
        // Don't re-handle the same share if the screen is recreated.
        i.action = null
        i.removeExtra("openItem")
    }

    private fun flushPending() {
        val p = synchronized(lock) {
            val x = pending
            pending = JSONObject()
            x
        }
        if (p.length() > 0) push(p.put("type", "pending"))
    }

    private fun push(o: JSONObject) {
        val js = "window.onNative && window.onNative(" + JSONObject.quote(o.toString()) + ")"
        ui.post { if (!destroyed && pageReady) web.evaluateJavascript(js, null) }
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun notifOk(): Boolean =
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false

    private fun openExternal(u: String) {
        if (!u.startsWith("https://") && !u.startsWith("http://")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (e: Exception) {
            push(JSONObject().put("type", "toast").put("text", "No app found to open that link."))
        }
    }

    private fun askNotifications() {
        if (notifOk()) {
            push(JSONObject().put("type", "toast").put("text", "Alerts are already on."))
            return
        }
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val asked = prefs.getInt("notifAsked", 0)
        if (Build.VERSION.SDK_INT >= 33 && asked < 2 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putInt("notifAsked", asked + 1).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            } catch (e: Exception) {
                push(JSONObject().put("type", "toast").put("text", "Turn on notifications for PricePing in your phone's Settings."))
            }
        }
    }

    private fun shareBackup() {
        val data = Store.read(this).toString()
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "PricePing backup")
            .putExtra(Intent.EXTRA_TEXT, data)
        val chooser = Intent.createChooser(send, "Save PricePing backup")
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        startActivity(chooser)
    }

    /** Methods the screens call. Anything slow runs off the UI thread and reports back via onNative(). */
    inner class Bridge {
        @JavascriptInterface
        fun ready(): String {
            pageReady = true
            return synchronized(lock) {
                val x = pending
                pending = JSONObject()
                x.toString()
            }
        }

        @JavascriptInterface
        fun getState(): String {
            val d = Store.read(applicationContext)
            d.put("dark", isDark())
            d.put("notifOk", notifOk())
            d.put("checking", Checker.running.get())
            return d.toString()
        }

        @JavascriptInterface
        fun addLink(text: String, itemId: String) {
            exec.execute {
                val r = try {
                    Checker.addLink(applicationContext, text, itemId)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", "Something went wrong: ${e.message}")
                }
                push(r.put("type", "added"))
            }
        }

        @JavascriptInterface
        fun checkNow(itemId: String) {
            exec.execute {
                val n = try { Checker.runAll(applicationContext, itemId.ifEmpty { null }) } catch (e: Exception) { 0 }
                push(JSONObject().put("type", "checked").put("count", n))
            }
        }

        @JavascriptInterface
        fun removeItem(id: String) {
            Store.edit(applicationContext) { Store.removeItem(it, id) }
        }

        @JavascriptInterface
        fun removeListing(id: String, listingId: String) {
            Store.edit(applicationContext) { d -> Store.findItem(d, id)?.let { Store.removeListing(it, listingId) } }
        }

        @JavascriptInterface
        fun renameItem(id: String, name: String) {
            val n = name.trim().take(160)
            if (n.isEmpty()) return
            Store.edit(applicationContext) { d -> Store.findItem(d, id)?.put("name", n) }
        }

        @JavascriptInterface
        fun setTarget(id: String, value: String) {
            val v = PriceParser.parseNum(value)
            Store.edit(applicationContext) { d -> Store.findItem(d, id)?.put("target", v ?: JSONObject.NULL) }
        }

        @JavascriptInterface
        fun saveSettings(json: String) {
            val inc = try { JSONObject(json) } catch (e: Exception) { return }
            Store.edit(applicationContext) { d ->
                val s = d.getJSONObject("settings")
                if (inc.has("intervalHours")) s.put("intervalHours", inc.optInt("intervalHours", 6).coerceIn(0, 24))
                if (inc.has("notifyMode")) s.put("notifyMode", if (inc.optString("notifyMode") == "target") "target" else "any")
                if (inc.has("minDropPct")) s.put("minDropPct", inc.optDouble("minDropPct", 0.0).coerceIn(0.0, 90.0))
                if (inc.has("wifiOnly")) s.put("wifiOnly", inc.optBoolean("wifiOnly"))
                if (inc.has("notifyStock")) s.put("notifyStock", inc.optBoolean("notifyStock"))
            }
            Scheduler.apply(applicationContext)
        }

        @JavascriptInterface
        fun openUrl(u: String) {
            ui.post { openExternal(u) }
        }

        @JavascriptInterface
        fun requestNotifications() {
            ui.post { askNotifications() }
        }

        @JavascriptInterface
        fun testNotification() {
            if (!notifOk()) {
                ui.post { askNotifications() }
                return
            }
            Notifier.show(applicationContext, "test", "PricePing alerts are on", "You'll get a notification like this when a price drops.")
        }

        @JavascriptInterface
        fun exportData() {
            ui.post { shareBackup() }
        }

        @JavascriptInterface
        fun importData(json: String): String = try {
            val d = JSONObject(json.trim())
            val items = d.getJSONArray("items")
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                if (!it.has("id") || !it.has("listings")) throw IllegalArgumentException()
            }
            Store.replace(applicationContext, JSONObject().put("items", items).put("settings", d.optJSONObject("settings") ?: JSONObject()))
            Scheduler.apply(applicationContext)
            "ok"
        } catch (e: Exception) {
            "That doesn't look like a PricePing backup."
        }
    }
}
