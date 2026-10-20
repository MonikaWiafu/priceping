package io.github.priceping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.random.Random

/** Fetches prices, records history, and decides when to ping. */
object Checker {
    val running = AtomicBoolean(false)
    private const val DAY = 24L * 3600 * 1000

    class Outcome(
        val title: String?,
        val price: Double?,
        val currency: String?,
        val image: String?,
        val inStock: Boolean?,
        val error: String?,
        val finalUrl: String,
    )

    private class Change(val oldPrice: Double?, val oldStock: Boolean?)

    fun extractUrl(text: String): String? =
        Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

    fun fetchListing(url: String): Outcome = try {
        val page = Fetcher.get(url)
        val r = PriceParser.parse(page.body, page.finalUrl)
        val err = when {
            r.price != null -> null
            r.blocked || page.code == 403 || page.code == 429 || page.code == 503 ->
                "The store blocked this check. PricePing will try again next time."
            page.code == 404 -> "This page no longer exists (404). The listing may have been removed."
            page.code >= 400 -> "The store's page returned an error (${page.code})."
            else -> "Couldn't find a price on this page."
        }
        Outcome(r.title, r.price, r.currency, r.image, r.inStock, err, page.finalUrl)
    } catch (e: Exception) {
        Outcome(null, null, null, null, null, "Couldn't reach the store (${e.message ?: e.javaClass.simpleName}).", url)
    }

    /** Adds a store link, either as a new item or to an existing item. */
    fun addLink(ctx: Context, text: String, itemId: String?): JSONObject {
        val raw = extractUrl(text)
            ?: return JSONObject().put("ok", false).put("error", "That doesn't look like a link. Paste the full product link (starting with https://).")
        val o = fetchListing(PriceParser.canonicalUrl(raw))
        val url = PriceParser.canonicalUrl(o.finalUrl)
        val host = PriceParser.hostOf(url)
        val storeName = PriceParser.storeName(host)
        val now = System.currentTimeMillis()

        return Store.edit(ctx) { data ->
            val items = data.getJSONArray("items")
            var item: JSONObject? = if (!itemId.isNullOrEmpty()) Store.findItem(data, itemId) else null
            if (item == null) {
                item = JSONObject()
                    .put("id", Store.newId())
                    .put("name", o.title ?: "$storeName item")
                    .put("created", now)
                    .put("target", JSONObject.NULL)
                    .put("listings", JSONArray())
                items.put(item)
            }
            val listings = item.getJSONArray("listings")
            var listing: JSONObject? = null
            for (i in 0 until listings.length()) {
                val l = listings.optJSONObject(i)
                if (l != null && l.optString("url") == url) listing = l
            }
            if (listing == null) {
                listing = JSONObject()
                    .put("id", Store.newId())
                    .put("url", url)
                    .put("store", storeName)
                    .put("added", now)
                    .put("history", JSONArray())
                listings.put(listing)
            }
            applyOutcome(listing, o, now)
            JSONObject()
                .put("ok", true)
                .put("itemId", item.getString("id"))
                .put("found", o.price != null)
                .put("price", o.price ?: JSONObject.NULL)
                .put("store", storeName)
                .put("error", o.error ?: JSONObject.NULL)
        }
    }

    private fun applyOutcome(l: JSONObject, o: Outcome, now: Long): Change {
        val oldPrice = Store.dbl(l, "price")
        val oldStock: Boolean? = if (l.has("inStock") && !l.isNull("inStock")) l.optBoolean("inStock") else null
        l.put("lastChecked", now)
        o.title?.let { l.put("title", it) }
        o.image?.let { l.put("image", it) }
        if (o.inStock != null) l.put("inStock", o.inStock)
        val p = o.price
        if (p != null) {
            l.put("price", p)
            l.put("currency", o.currency ?: "USD")
            l.put("error", JSONObject.NULL)
            l.put("lastOk", now)
            val low = Store.dbl(l, "lowest")
            if (low == null || p < low) l.put("lowest", p)
            var h = l.optJSONArray("history")
            if (h == null) {
                h = JSONArray()
                l.put("history", h)
            }
            val last = if (h.length() > 0) h.optJSONArray(h.length() - 1) else null
            val lastTs = last?.optLong(0) ?: 0L
            val lastP = last?.optDouble(1) ?: Double.NaN
            if (last == null || kotlin.math.abs(lastP - p) > 0.004 || now - lastTs > DAY) {
                h.put(JSONArray().put(now).put(p))
                while (h.length() > 400) h.remove(0)
            }
        } else {
            l.put("error", o.error ?: "Couldn't read the price.")
        }
        return Change(oldPrice, oldStock)
    }

    /**
     * Checks every listing (or just one item's). Returns how many alerts were sent,
     * or -1 if a check was already running.
     */
    fun runAll(ctx: Context, onlyItem: String?): Int {
        if (!running.compareAndSet(false, true)) return -1
        Store.touch()
        var alertsSent = 0
        try {
            class Target(val itemId: String, val listingId: String, val url: String, val lastChecked: Long)
            val targets = ArrayList<Target>()
            val snap = Store.read(ctx)
            val items = snap.getJSONArray("items")
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val iid = it.optString("id")
                if (onlyItem != null && iid != onlyItem) continue
                val ls = it.optJSONArray("listings") ?: continue
                for (j in 0 until ls.length()) {
                    val l = ls.optJSONObject(j) ?: continue
                    targets.add(Target(iid, l.optString("id"), l.optString("url"), l.optLong("lastChecked", 0)))
                }
            }
            // Stalest first, so if Android cuts a run short the next run picks up where it left off.
            targets.sortBy { it.lastChecked }

            for ((n, t) in targets.withIndex()) {
                if (Thread.currentThread().isInterrupted) break
                if (n > 0) Thread.sleep(1500L + Random.nextLong(2500L)) // be polite to stores
                val o = fetchListing(t.url)
                val alerts = ArrayList<Array<String>>()
                Store.edit(ctx) { data ->
                    val item = Store.findItem(data, t.itemId) ?: return@edit
                    val l = Store.findListing(item, t.listingId) ?: return@edit
                    val ch = applyOutcome(l, o, System.currentTimeMillis())
                    collectAlerts(data.getJSONObject("settings"), item, l, o, ch, alerts)
                }
                for (a in alerts) {
                    Notifier.show(ctx, a[0], a[1], a[2])
                    alertsSent++
                }
            }
            if (onlyItem == null) {
                Store.edit(ctx) { it.getJSONObject("settings").put("lastRun", System.currentTimeMillis()) }
            }
        } catch (e: InterruptedException) {
            // Android asked us to stop; the job is rescheduled automatically.
        } finally {
            running.set(false)
            Store.touch()
        }
        return alertsSent
    }

    private fun collectAlerts(
        s: JSONObject, item: JSONObject, l: JSONObject, o: Outcome, ch: Change, out: MutableList<Array<String>>,
    ) {
        val itemId = item.optString("id")
        val name = item.optString("name", "Your item")
        val store = l.optString("store", "the store")
        val newP = o.price
        val oldP = ch.oldPrice

        if (newP != null && oldP != null && newP < oldP - 0.004) {
            val pct = (oldP - newP) / oldP * 100.0
            val target = Store.dbl(item, "target")
            val hit = target != null && newP <= target + 0.001
            val want = if (s.optString("notifyMode") == "target") hit else (pct >= s.optDouble("minDropPct", 0.0) || hit)
            if (want) {
                val priced = pricedCount(item)
                val extra = StringBuilder()
                if (priced > 1 && isBest(item, l.optString("id"))) extra.append("\nBest price of $priced stores.")
                if (hit) extra.append("\nAt or below your target of ${money(target!!)}.")
                out.add(arrayOf(
                    itemId,
                    "Price drop: ${money(newP)} at $store",
                    "$name\nWas ${money(oldP)} (down ${pct.roundToInt()}%).$extra",
                ))
            }
        }
        if (s.optBoolean("notifyStock", true) && ch.oldStock == false && o.inStock == true) {
            val p = newP?.let { " for ${money(it)}" } ?: ""
            out.add(arrayOf(itemId, "Back in stock at $store", "$name is available again$p."))
        }
    }

    private fun pricedCount(item: JSONObject): Int {
        val ls = item.optJSONArray("listings") ?: return 0
        var n = 0
        for (i in 0 until ls.length()) if (ls.optJSONObject(i)?.let { Store.dbl(it, "price") } != null) n++
        return n
    }

    private fun isBest(item: JSONObject, listingId: String): Boolean {
        val ls = item.optJSONArray("listings") ?: return false
        var bestId: String? = null
        var bestP = Double.MAX_VALUE
        for (i in 0 until ls.length()) {
            val l = ls.optJSONObject(i) ?: continue
            val p = Store.dbl(l, "price") ?: continue
            if (p < bestP) { bestP = p; bestId = l.optString("id") }
        }
        return bestId == listingId
    }

    fun money(v: Double): String = String.format(Locale.US, "$%,.2f", v)
}
