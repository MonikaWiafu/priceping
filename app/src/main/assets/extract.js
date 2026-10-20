/* Runs inside a hidden browser on a store's search page and returns the products it shows.
   Each store has its own reader (tested on the live sites), plus a generic fallback. */
(function () {
  var MAX = 40;
  function num(s) {
    if (s == null) return null;
    var m = String(s).replace(/,/g, '').match(/\d+(\.\d+)?/);
    if (!m) return null;
    var v = parseFloat(m[0]);
    return v > 0 && v < 1000000 ? v : null;
  }
  function txt(el) { return el ? el.textContent.replace(/ /g, ' ').replace(/\s+/g, ' ').trim() : ''; }
  function abs(u) { try { return u ? new URL(u, location.href).href : null; } catch (e) { return null; } }
  function blocked() {
    var t = (document.title + ' ' + (document.body ? document.body.innerText.slice(0, 2000) : '')).toLowerCase();
    return /robot|captcha|access denied|are you a human|pardon our interruption|verify you are|access to this page has been denied/.test(t);
  }
  function scriptsText() {
    var s = document.scripts, out = [];
    for (var i = 0; i < s.length; i++) if (s[i].textContent.length > 200) out.push(s[i].textContent);
    return out.join('\n').replace(/\\"/g, '"');
  }

  function amazon() {
    var out = [];
    document.querySelectorAll('div[data-component-type="s-search-result"][data-asin]').forEach(function (c) {
      var asin = c.getAttribute('data-asin');
      if (!asin) return;
      var title = '';
      c.querySelectorAll('h2').forEach(function (h) { var t = txt(h); if (t.length > title.length) title = t; });
      var brand = c.querySelector('h2.a-size-mini');
      if (brand && title.indexOf(txt(brand)) < 0 && txt(brand).length < 40) title = txt(brand) + ' ' + title;
      var pe = c.querySelector('.a-price:not(.a-text-price) .a-offscreen');
      var we = c.querySelector('.a-price.a-text-price .a-offscreen');
      var img = c.querySelector('img.s-image');
      var r = c.querySelector('[aria-label*="out of 5 stars"]');
      var rv = c.querySelector('a[href*="customerReviews"] span, span.s-underline-text');
      out.push({
        title: title, price: pe ? num(pe.textContent) : null, was: we ? num(we.textContent) : null,
        image: img && img.src, rating: r ? num(r.getAttribute('aria-label')) : null,
        reviews: rv ? num(rv.getAttribute('aria-label') || rv.textContent) : null,
        url: 'https://www.amazon.com/dp/' + asin, id: asin,
        sponsored: !!c.querySelector('.puis-sponsored-label-text')
      });
    });
    return out;
  }

  function walmart() {
    var nd = document.getElementById('__NEXT_DATA__');
    if (!nd) return [];
    var j; try { j = JSON.parse(nd.textContent); } catch (e) { return []; }
    var sr = j && j.props && j.props.pageProps && j.props.pageProps.initialData && j.props.pageProps.initialData.searchResult;
    var stacks = (sr && sr.itemStacks) || [], out = [];
    stacks.forEach(function (s) {
      (s.items || []).forEach(function (it) {
        if (it.__typename !== 'Product' || !it.name) return;
        var price = num(it.price), was = null;
        var pl = it.priceInfo && it.priceInfo.priceDetails && it.priceInfo.priceDetails.priceLines;
        (pl || []).forEach(function (l) { (l.values || []).forEach(function (v) {
          if (v.key === 'PRICE' && price == null) price = num(v.value);
          if (v.key === 'WAS_PRICE') was = num(v.value);
        }); });
        if (price == null && it.priceInfo) price = num(it.priceInfo.linePrice || it.priceInfo.itemPrice);
        var st = it.availabilityStatusV2 && it.availabilityStatusV2.value;
        out.push({
          title: it.name, price: price, was: was, image: it.image || (it.imageInfo && it.imageInfo.thumbnailUrl),
          rating: it.averageRating || null, reviews: it.numberOfReviews || null,
          url: abs((it.canonicalUrl || '').split('?')[0]), id: it.usItemId,
          sponsored: !!it.isSponsoredFlag, inStock: st ? st === 'IN_STOCK' : null,
          seller: it.sellerName && it.sellerName !== 'Walmart.com' ? it.sellerName : null
        });
      });
    });
    return out;
  }

  function bestbuy() {
    var t = scriptsText(), pm = {}, m;
    var re1 = /"price":\{"customerPrice":([\d.]+)([^{}]*?)"skuId":"(\d+)"/g;
    while ((m = re1.exec(t))) if (!pm[m[3]]) pm[m[3]] = { p: +m[1], r: num((m[2].match(/"regularPrice":([\d.]+)/) || [])[1]) };
    var re2 = /"skuId":"(\d+)"[^{}]*?"customerPrice":([\d.]+)/g;
    while ((m = re2.exec(t))) if (!pm[m[1]]) pm[m[1]] = { p: +m[2] };
    var out = [];
    document.querySelectorAll('li.product-list-item, li.sku-item').forEach(function (c) {
      var sku = c.getAttribute('data-product-id') || c.getAttribute('data-sku-id') || c.getAttribute('data-testid');
      var a = c.querySelector('a.product-list-item-link, a[href*="/product/"], a[href*="/site/"]');
      var h = c.querySelector('h3.product-title, h4.sku-title, h3, h4');
      var img = c.querySelector('img[data-testid="product-image"], img.product-image, img');
      var rt = (c.innerText.match(/Rating ([\d.]+) out of 5 stars with ([\d,]+) review/) || []);
      var price = pm[sku] ? pm[sku].p : null;
      if (price == null) { var dm = c.innerText.match(/\$\s?([\d,]+\.\d{2})/); if (dm) price = num(dm[1]); }
      if (!h || !a) return;
      out.push({
        title: (h.getAttribute('title') || txt(h)).replace(/ /g, ' '), price: price,
        was: pm[sku] && pm[sku].r && pm[sku].r > price ? pm[sku].r : null,
        image: img && img.src, rating: num(rt[1]), reviews: num(rt[2]), url: a.href.split('?')[0], id: sku
      });
    });
    return out;
  }

  function ebay() {
    var out = [];
    document.querySelectorAll('li.s-card[data-listingid], li.s-item').forEach(function (c) {
      var t = c.querySelector('.s-card__title, .s-item__title');
      var title = txt(t).replace(/^New Listing/i, '').replace(/Opens in a new window or tab$/i, '').trim();
      var a = c.querySelector('a[href*="/itm/"]');
      var id = a && (a.href.match(/\/itm\/(\d+)/) || [])[1];
      if (!id || !title || /^Shop on eBay$/i.test(title)) return;
      var pe = c.querySelector('.s-card__price, .s-item__price');
      var img = c.querySelector('img.s-card__image, .s-item__image img');
      var strike = c.querySelector('.strikethrough, .STRIKETHROUGH, s');
      var cond = txt(c.querySelector('.s-card__subtitle, .SECONDARY_INFO'));
      out.push({
        title: title, price: pe ? num(pe.textContent) : null, was: strike ? num(strike.textContent) : null,
        image: img && (img.getAttribute('data-defer-load') || img.src), url: 'https://www.ebay.com/itm/' + id, id: id,
        condition: /new|used|pre-owned|refurb|open box|parts/i.test(cond) ? cond.split('·')[0].trim() : null,
        priceRange: pe ? / to /.test(pe.textContent) : false
      });
    });
    return out;
  }

  function newegg() {
    var st = window.__initialState__, out = [];
    var list = st && (st.Products || (st.SearchResult && st.SearchResult.Products));
    if (Array.isArray(list)) {
      list.forEach(function (p) {
        var ic = p.ItemCell; if (!ic || !ic.Description) return;
        var item = ic.Item || '';
        var id = /^\d{2}-\d{3}-\d{3}/.test(item) ? 'N82E168' + item.replace(/\D/g, '') : item;
        var img = ic.Image && ic.Image.ItemCellImageName;
        var price = num(ic.FinalPrice), was = num(ic.UnitCost);
        out.push({
          title: ic.Description.Title, price: price, was: was && price && was > price ? was : null,
          image: img ? 'https://c1.neweggimages.com/ProductImageCompressAll300/' + img : null,
          rating: ic.Review ? (ic.Review.RatingOneDecimal || ic.Review.Rating || null) : null,
          reviews: ic.Review ? ic.Review.HumanRating || null : null,
          url: 'https://www.newegg.com/p/' + id, id: id, inStock: typeof ic.Instock === 'boolean' ? ic.Instock : null
        });
      });
    }
    if (out.length) return out;
    document.querySelectorAll('.item-container').forEach(function (c) {
      var a = c.querySelector('a.item-title'), p = c.querySelector('.price-current');
      if (!a || !p) return;
      var strong = p.querySelector('strong'), sup = p.querySelector('sup');
      var img = c.querySelector('a.item-img img');
      out.push({ title: txt(a), url: a.href.split('?')[0], price: strong ? num(txt(strong) + txt(sup)) : num(txt(p)),
        image: img && (img.getAttribute('data-src') || img.src) });
    });
    return out;
  }

  function target() {
    var out = [];
    document.querySelectorAll('[data-test="@web/site-top-of-funnel/ProductCardWrapper"], [data-test*="ProductCard"]').forEach(function (c) {
      var a = c.querySelector('a[href*="/-/A-"], a[href*="/p/"]');
      var p = c.querySelector('[data-test="current-price"], [data-test*="price"]');
      var img = c.querySelector('img');
      if (!a) return;
      out.push({ title: txt(c.querySelector('[data-test="product-title"]')) || txt(a), url: a.href.split('?')[0],
        price: p ? num(txt(p)) : null, image: img && img.src,
        rating: num((c.querySelector('[data-test="ratings"]') || {}).textContent) });
    });
    return out.length ? out : generic();
  }

  // Fallback for any store: product links whose card shows a $ price.
  function generic() {
    var out = [], seen = {};
    document.querySelectorAll('a[href]').forEach(function (a) {
      if (out.length >= MAX) return;
      var href = a.href;
      if (seen[href] || !/\/(p|dp|product|ip|itm)\/|\.p\?|\/A-\d/.test(href)) return;
      var card = a, i = 0;
      while (card.parentElement && i++ < 6 && !/\$\s?\d/.test(card.innerText || '')) card = card.parentElement;
      var pm = (card.innerText || '').match(/\$\s?([\d,]+(?:\.\d{2})?)/);
      var title = txt(a) || (a.querySelector('img') || {}).alt || '';
      if (!pm || title.length < 8) return;
      seen[href] = 1;
      var img = card.querySelector('img');
      out.push({ title: title, url: href.split('?')[0], price: num(pm[1]), image: img && img.src });
    });
    return out;
  }

  var h = location.hostname, items = [];
  try {
    if (/amazon\./.test(h)) items = amazon();
    else if (/walmart\./.test(h)) items = walmart();
    else if (/bestbuy\./.test(h)) items = bestbuy();
    else if (/ebay\./.test(h)) items = ebay();
    else if (/newegg\./.test(h)) items = newegg();
    else if (/target\./.test(h)) items = target();
    if (!items.length) items = generic();
  } catch (e) { items = []; }

  var seenUrl = {}, clean = [];
  items.forEach(function (o) {
    if (!o || !o.url || !o.title || o.price == null || seenUrl[o.url]) return;
    seenUrl[o.url] = 1;
    if (o.image && !/^https:/.test(o.image)) o.image = null;
    clean.push(o);
  });
  return JSON.stringify({ items: clean.slice(0, MAX), blocked: !clean.length && blocked(), ready: document.readyState === 'complete', host: h });
})();
