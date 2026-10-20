package io.github.priceping

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class ParseResult(
    val title: String?,
    val price: Double?,
    val currency: String?,
    val image: String?,
    val inStock: Boolean?,
    val blocked: Boolean,
    val source: String,
)

/**
 * Reads a product's title, price, image and stock status out of a store page's HTML.
 *
 * Order of attempts:
 *  1. Store-specific markup (Amazon first, since it rarely ships structured data)
 *  2. schema.org Product data (JSON-LD) — most big US stores publish this
 *  3. Store-specific embedded JSON (Walmart, Target, Best Buy, Newegg, eBay)
 *  4. Open Graph / product meta tags and microdata
 *  5. A last-resort guess from visible "$12.34" text inside a price-classed element
 */
object PriceParser {
    private val DOT = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private class Acc {
        var title: String? = null
        var price: Double? = null
        var currency: String? = null
        var image: String? = null
        var inStock: Boolean? = null
        var source = ""

        /** Sets the price if none is set yet. Returns true once a price is known. */
        fun setPrice(p: Double?, cur: String?, src: String): Boolean {
            if (price != null) return true
            if (p == null) return false
            price = p
            if (currency == null) currency = cur?.trim()?.takeIf { it.length == 3 }?.uppercase()
            source = src
            return true
        }
    }

    fun parse(html: String, url: String): ParseResult {
        val host = hostOf(url)
        val acc = Acc()
        val metas = metaTags(html)

        if (host.contains("amazon.")) amazon(html, acc)
        jsonLd(html, acc)
        when {
            host.contains("walmart.") -> walmart(html, acc)
            host.contains("target.com") -> target(html, acc)
            host.contains("bestbuy.") -> bestbuy(html, acc)
            host.contains("newegg.") -> newegg(html, acc)
            host.contains("ebay.") -> ebay(html, acc)
        }
        fromMeta(metas, acc)
        microdata(html, acc)
        visiblePrice(html, acc)

        val blocked = acc.price == null && looksBlocked(html)
        if (!blocked) {
            if (acc.title == null) {
                acc.title = metas["og:title"] ?: metas["twitter:title"]
                    ?: Regex("<title[^>]*>(.*?)</title>", DOT).find(html)?.groupValues?.get(1)
            }
            if (acc.image == null) acc.image = metas["og:image"] ?: metas["twitter:image"]
        } else {
            acc.title = null
        }
        val title = acc.title?.let { cleanTitle(it) }?.takeIf { it.isNotBlank() }
        val image = acc.image?.let { normImage(it) }
        val currency = if (acc.price != null) (acc.currency ?: "USD") else null
        return ParseResult(title, acc.price, currency, image, acc.inStock, blocked, acc.source)
    }

    // ---------- Store-specific ----------

    private fun amazon(html: String, acc: Acc) {
        Regex("""id=["']productTitle["'][^>]*>(.*?)</span>""", DOT).find(html)?.let { acc.title = it.groupValues[1] }

        val anchors = listOf(
            "corePriceDisplay_desktop_feature_div", "corePrice_desktop", "corePrice_feature_div",
            "apex_desktop", "price_inside_buybox", "priceblock_dealprice", "priceblock_ourprice",
            "newBuyBoxPrice", "tp_price_block_total_price_ww",
        )
        for (a in anchors) {
            var i = html.indexOf("id=\"$a\"")
            if (i < 0) i = html.indexOf(a)
            if (i < 0) continue
            var seg = html.substring(i, minOf(html.length, i + 8000))
            val k = seg.indexOf("priceToPay")
            if (k >= 0) seg = seg.substring(k)
            val p = amazonOffscreen(seg) ?: amazonWholeFraction(seg) ?: plainDollar(seg)
            if (acc.setPrice(p, "USD", "amazon")) break
        }
        if (acc.price == null) {
            val m = Regex(""""priceAmount"\s*:\s*([\d.]+)""").find(html)
            acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "amazon")
        }

        val img = Regex(""""hiRes"\s*:\s*"(https://[^"]+)"""").find(html)
            ?: Regex("""data-old-hires=["'](https://[^"']+)""").find(html)
            ?: Regex("""id=["']landingImage["'][^>]*?src=["'](https://[^"']+)""").find(html)
        if (img != null) acc.image = img.groupValues[1]

        val ai = html.indexOf("id=\"availability\"")
        if (ai >= 0) {
            val seg = stripTags(html.substring(ai, minOf(html.length, ai + 1500))).lowercase()
            acc.inStock = when {
                seg.contains("currently unavailable") || seg.contains("out of stock") -> false
                seg.contains("in stock") -> true
                else -> null
            }
        }
        if (acc.inStock == null && html.contains("id=\"add-to-cart-button\"")) acc.inStock = true
    }

    private fun amazonOffscreen(seg: String): Double? =
        Regex("""class=["']a-offscreen["']\s*>\s*([^<]*?\d[^<]*?)\s*<""").find(seg)?.groupValues?.get(1)?.let { parseNum(it) }

    private fun amazonWholeFraction(seg: String): Double? {
        val m = Regex("""a-price-whole["']\s*>\s*([\d,]+).{0,160}?a-price-fraction["']\s*>\s*(\d{1,2})""", DOT).find(seg)
            ?: return null
        return parseNum(m.groupValues[1].replace(",", "") + "." + m.groupValues[2])
    }

    private fun plainDollar(seg: String): Double? =
        Regex(""">\s*\$\s*([\d,]+\.\d{2})\s*<""").find(seg)?.groupValues?.get(1)?.let { parseNum(it) }

    private fun walmart(html: String, acc: Acc) {
        val m = Regex(""""currentPrice"\s*:\s*\{[^{}]*?"price"\s*:\s*([\d.]+)""").find(html)
        acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "walmart")
        if (acc.inStock == null) {
            Regex(""""availabilityStatus"\s*:\s*"([A-Z_]+)"""").find(html)?.let {
                acc.inStock = when (it.groupValues[1]) { "IN_STOCK" -> true; "OUT_OF_STOCK" -> false; else -> null }
            }
        }
    }

    private fun target(html: String, acc: Acc) {
        val m = Regex("""\\?"current_retail\\?"\s*:\s*([\d.]+)""").find(html)
            ?: Regex("""\\?"formatted_current_price\\?"\s*:\s*\\?"\$?([\d,.]+)""").find(html)
        acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "target")
    }

    private fun bestbuy(html: String, acc: Acc) {
        val m = Regex(""""customerPrice"\s*:\s*([\d.]+)""").find(html)
            ?: Regex(""""currentPrice"\s*:\s*([\d.]+)""").find(html)
            ?: Regex("""customer-price.{0,300}?\$([\d,]+\.\d{2})""", DOT).find(html)
        acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "bestbuy")
    }

    private fun newegg(html: String, acc: Acc) {
        val m = Regex(""""FinalPrice"\s*:\s*([\d.]+)""").find(html)
        acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "newegg")
    }

    private fun ebay(html: String, acc: Acc) {
        val m = Regex("""x-price-primary.{0,400}?\$\s*([\d,]+\.\d{2})""", DOT).find(html)
        acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "ebay")
    }

    // ---------- JSON-LD (schema.org Product) ----------

    private val LD_RE = Regex("""<script[^>]*type\s*=\s*["']?application/ld\+json["']?[^>]*>(.*?)</script>""", DOT)

    private fun jsonLd(html: String, acc: Acc) {
        for (m in LD_RE.findAll(html)) {
            var raw = m.groupValues[1].trim()
            raw = raw.removePrefix("<!--").removeSuffix("-->").removePrefix("//<![CDATA[").removeSuffix("//]]>").trim()
            val v: Any? = try { JSONTokener(raw).nextValue() } catch (e: Exception) { null }
            if (v == null) continue
            val products = ArrayList<JSONObject>()
            collectProducts(v, products, 0)
            for (p in products) {
                applyProduct(p, acc)
                if (acc.price != null) return
            }
        }
    }

    private fun collectProducts(v: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (depth > 8) return
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) collectProducts(v.opt(i), out, depth + 1)
            is JSONObject -> {
                if (isType(v, "Product") || isType(v, "ProductGroup") || (v.has("offers") && v.has("name"))) out.add(v)
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "offers") continue
                    val c = v.opt(k)
                    if (c is JSONObject || c is JSONArray) collectProducts(c, out, depth + 1)
                }
            }
        }
    }

    private fun isType(o: JSONObject, t: String): Boolean {
        fun m(x: Any?) = x is String && (x.equals(t, ignoreCase = true) || x.endsWith("/$t", ignoreCase = true))
        val v = o.opt("@type")
        if (m(v)) return true
        if (v is JSONArray) for (i in 0 until v.length()) if (m(v.opt(i))) return true
        return false
    }

    private fun applyProduct(p: JSONObject, acc: Acc) {
        if (acc.title == null) acc.title = str(p.opt("name"))
        if (acc.image == null) acc.image = imageOf(p.opt("image"))
        if (acc.price != null) return
        for (o in asObjects(p.opt("offers"))) {
            val pr = num(o.opt("price"))
                ?: num(o.opt("lowPrice"))
                ?: asObjects(o.opt("priceSpecification")).firstNotNullOfOrNull { num(it.opt("price")) }
                ?: asObjects(o.opt("offers")).firstNotNullOfOrNull { num(it.opt("price")) }
            if (pr != null) {
                val cur = str(o.opt("priceCurrency"))
                    ?: asObjects(o.opt("priceSpecification")).firstNotNullOfOrNull { str(it.opt("priceCurrency")) }
                acc.setPrice(pr, cur, "json-ld")
                if (acc.inStock == null) acc.inStock = str(o.opt("availability"))?.let { stockFrom(it) }
                return
            }
        }
    }

    private fun asObjects(v: Any?): List<JSONObject> = when (v) {
        is JSONObject -> listOf(v)
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it) as? JSONObject }
        else -> emptyList()
    }

    private fun imageOf(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.isNotBlank() }
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { imageOf(v.opt(it)) }
        is JSONObject -> str(v.opt("url")) ?: str(v.opt("contentUrl"))
        else -> null
    }

    // ---------- Meta tags & microdata ----------

    private val META_RE = Regex("""<meta\s([^>]+)>""", RegexOption.IGNORE_CASE)
    private val ATTR_RE = Regex("""([a-zA-Z_:.\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun metaTags(html: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (mt in META_RE.findAll(html)) {
            val attrs = HashMap<String, String>()
            for (a in ATTR_RE.findAll(mt.groupValues[1])) {
                attrs[a.groupValues[1].lowercase()] = a.groupValues[2].ifEmpty { a.groupValues[3] }
            }
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.lowercase() ?: continue
            val content = attrs["content"] ?: continue
            if (!out.containsKey(key)) out[key] = decode(content)
        }
        return out
    }

    private fun fromMeta(m: Map<String, String>, acc: Acc) {
        val p = m["product:price:amount"] ?: m["og:price:amount"] ?: m["price"]
        val cur = m["product:price:currency"] ?: m["og:price:currency"] ?: m["pricecurrency"]
        acc.setPrice(p?.let { parseNum(it) }, cur, "meta")
        if (acc.inStock == null) {
            val a = m["product:availability"] ?: m["og:availability"] ?: m["availability"]
            if (a != null) acc.inStock = stockFrom(a)
        }
    }

    private fun microdata(html: String, acc: Acc) {
        if (acc.price != null) return
        val m = Regex("""itemprop=["']price["'][^>]*?content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""content=["']([^"']+)["'][^>]*?itemprop=["']price["']""", RegexOption.IGNORE_CASE).find(html)
        acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "microdata")
    }

    private fun visiblePrice(html: String, acc: Acc) {
        if (acc.price != null) return
        val m = Regex("""class=["'][^"']*\bprice\b[^"']*["'][^>]*>\s*(?:<[^>]+>\s*){0,4}\$\s*([\d,]+\.\d{2})""", RegexOption.IGNORE_CASE).find(html)
        acc.setPrice(m?.groupValues?.get(1)?.let { parseNum(it) }, "USD", "guess")
    }

    // ---------- Helpers ----------

    private fun looksBlocked(html: String): Boolean {
        val l = (if (html.length > 300_000) html.substring(0, 300_000) else html).lowercase()
        return l.contains("validatecaptcha") ||
            l.contains("api-services-support@amazon.com") ||
            l.contains("px-captcha") ||
            l.contains("robot or human") ||
            l.contains("are you a robot") ||
            l.contains("are you a human") ||
            l.contains("pardon our interruption") ||
            (l.contains("captcha") && html.length < 40_000) ||
            (l.contains("access denied") && html.length < 10_000)
    }

    private fun stockFrom(s: String): Boolean? {
        val l = s.lowercase()
        return when {
            l.contains("outofstock") || l.contains("out of stock") || l.contains("soldout") ||
                l.contains("sold out") || l.contains("discontinued") -> false
            l.contains("instock") || l.contains("in stock") || l.contains("limitedavailability") ||
                l.contains("onlineonly") || l.contains("preorder") -> true
            else -> null
        }
    }

    private fun str(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.isNotBlank() }
        is Number -> v.toString()
        else -> null
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it > 0 && it < 1_000_000 }
        is String -> parseNum(v)
        else -> null
    }

    /** "$1,299.99" -> 1299.99, "12,99" -> 12.99, "19.99 - 29.99" -> 19.99 */
    fun parseNum(s: String): Double? {
        val t = Regex("""\d[\d,]*(?:\.\d+)?""").find(s)?.value ?: return null
        val norm = when {
            Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""").matches(t) -> t.replace(",", "")
            Regex("""^\d+,\d{2}$""").matches(t) -> t.replace(",", ".")
            else -> t.replace(",", "")
        }
        val v = norm.toDoubleOrNull() ?: return null
        return if (v > 0 && v < 1_000_000) v else null
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), " ")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "reg" to "®", "trade" to "™", "copy" to "©", "ndash" to "–", "mdash" to "—",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "hellip" to "…",
    )

    fun decode(s: String): String {
        if (!s.contains('&')) return s
        return Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
                else -> NAMED[e.lowercase()] ?: m.value
            }
        }
    }

    private fun cp(i: Int): String = try { String(Character.toChars(i)) } catch (e: Exception) { "" }

    private val STORE_SUFFIX = Regex(
        """\s*[|:–-]\s*(Best Buy|Walmart\.com|Walmart|Target|eBay|Newegg\.com|Newegg|Costco|The Home Depot|Home Depot|Lowe's|B&H Photo Video|Amazon\.com|Micro Center|GameStop|Kohl's|Macy's)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun cleanTitle(raw: String): String {
        var t = decode(stripTags(raw)).replace(Regex("""\s+"""), " ").trim()
        t = t.replace(Regex("""^Amazon\.com\s*:\s*""", RegexOption.IGNORE_CASE), "")
        t = t.replace(STORE_SUFFIX, "").trim()
        if (t.length > 160) t = t.substring(0, 157).trimEnd() + "…"
        return t
    }

    private fun normImage(s: String): String? {
        var u = decode(s.trim())
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        return u.takeIf { it.startsWith("https://") && it.length < 2000 }
    }

    fun hostOf(url: String): String =
        Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://([^/:?#]+)""").find(url.trim())?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: ""

    private val STORES = linkedMapOf(
        "amazon.com" to "Amazon", "bestbuy.com" to "Best Buy", "walmart.com" to "Walmart",
        "target.com" to "Target", "ebay.com" to "eBay", "newegg.com" to "Newegg",
        "costco.com" to "Costco", "homedepot.com" to "Home Depot", "lowes.com" to "Lowe's",
        "bhphotovideo.com" to "B&H Photo", "microcenter.com" to "Micro Center", "gamestop.com" to "GameStop",
        "kohls.com" to "Kohl's", "macys.com" to "Macy's", "adorama.com" to "Adorama",
        "samsclub.com" to "Sam's Club", "staples.com" to "Staples", "officedepot.com" to "Office Depot",
        "apple.com" to "Apple", "samsung.com" to "Samsung", "dell.com" to "Dell", "lenovo.com" to "Lenovo",
        "hp.com" to "HP", "aliexpress.com" to "AliExpress", "aliexpress.us" to "AliExpress", "temu.com" to "Temu",
        "etsy.com" to "Etsy", "wayfair.com" to "Wayfair", "chewy.com" to "Chewy", "nike.com" to "Nike",
        "rei.com" to "REI", "dickssportinggoods.com" to "Dick's", "ikea.com" to "IKEA",
        "sephora.com" to "Sephora", "ulta.com" to "Ulta",
    )

    fun storeName(host: String): String {
        for ((d, name) in STORES) if (host == d || host.endsWith(".$d")) return name
        if (host.contains("amazon.")) return "Amazon"
        val parts = host.split('.').filter { it.isNotEmpty() }
        val sld = setOf("co", "com", "org", "net", "ac", "gov")
        val base = when {
            parts.size >= 3 && parts[parts.size - 2] in sld -> parts[parts.size - 3]
            parts.size >= 2 -> parts[parts.size - 2]
            else -> host
        }
        return base.replaceFirstChar { it.uppercaseChar() }.ifEmpty { "Store" }
    }

    /** Strips tracking junk where we safely can (Amazon links collapse to /dp/ASIN). */
    fun canonicalUrl(url: String): String {
        val u = url.trim()
        val host = hostOf(u)
        if (host.contains("amazon.")) {
            Regex("""/(?:dp|gp/product|gp/aw/d|product)/([A-Z0-9]{10})""").find(u)?.let {
                return "https://www.${host.removePrefix("smile.")}/dp/${it.groupValues[1]}"
            }
        }
        return u
    }
}
