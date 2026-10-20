package io.github.priceping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * All app data lives in one small JSON file on the phone:
 * { items: [ { id, name, target, listings: [ { id, url, store, price, history: [[time, price]], ... } ] } ],
 *   settings: { intervalHours, notifyMode, minDropPct, wifiOnly, notifyStock, lastRun } }
 */
object Store {
    private val lock = Any()
    @Volatile var onChange: (() -> Unit)? = null

    fun touch() {
        onChange?.invoke()
    }

    fun read(ctx: Context): JSONObject = synchronized(lock) { load(ctx) }

    fun <T> edit(ctx: Context, block: (JSONObject) -> T): T {
        val result = synchronized(lock) {
            val d = load(ctx)
            val r = block(d)
            save(ctx, d)
            r
        }
        touch()
        return result
    }

    fun replace(ctx: Context, data: JSONObject) {
        synchronized(lock) { save(ctx, data) }
        touch()
    }

    private fun load(ctx: Context): JSONObject {
        val f = File(ctx.filesDir, "data.json")
        var d = JSONObject()
        if (f.exists()) {
            try {
                d = JSONObject(f.readText())
            } catch (e: Exception) {
                try { f.copyTo(File(ctx.filesDir, "data.corrupt.json"), overwrite = true) } catch (e2: Exception) { }
            }
        }
        if (d.optJSONArray("items") == null) d.put("items", JSONArray())
        var s = d.optJSONObject("settings")
        if (s == null) {
            s = JSONObject()
            d.put("settings", s)
        }
        if (!s.has("intervalHours")) s.put("intervalHours", 6)
        if (!s.has("notifyMode")) s.put("notifyMode", "any")
        if (!s.has("minDropPct")) s.put("minDropPct", 1)
        if (!s.has("wifiOnly")) s.put("wifiOnly", false)
        if (!s.has("notifyStock")) s.put("notifyStock", true)
        if (s.optJSONArray("stores") == null) {
            s.put("stores", JSONArray().put("amazon").put("walmart").put("bestbuy").put("target").put("ebay").put("newegg"))
        }
        return d
    }

    private fun save(ctx: Context, d: JSONObject) {
        val f = File(ctx.filesDir, "data.json")
        val tmp = File(ctx.filesDir, "data.json.tmp")
        val text = d.toString()
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 10)

    fun findItem(d: JSONObject, id: String): JSONObject? {
        val items = d.getJSONArray("items")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.optString("id") == id) return it
        }
        return null
    }

    fun findListing(item: JSONObject, id: String): JSONObject? {
        val ls = item.optJSONArray("listings") ?: return null
        for (i in 0 until ls.length()) {
            val l = ls.optJSONObject(i) ?: continue
            if (l.optString("id") == id) return l
        }
        return null
    }

    fun removeItem(d: JSONObject, id: String) {
        val items = d.getJSONArray("items")
        for (i in items.length() - 1 downTo 0) {
            if (items.optJSONObject(i)?.optString("id") == id) items.remove(i)
        }
    }

    fun removeListing(item: JSONObject, id: String) {
        val ls = item.optJSONArray("listings") ?: return
        for (i in ls.length() - 1 downTo 0) {
            if (ls.optJSONObject(i)?.optString("id") == id) ls.remove(i)
        }
    }

    /** Reads a number field, treating missing / null as null. */
    fun dbl(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optDouble(key)
        return if (v.isNaN()) null else v
    }
}
