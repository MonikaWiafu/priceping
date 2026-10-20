# PricePing

A free, open-source Android price tracker. Share a product from any store (Amazon, Best Buy, Walmart, Target, eBay, Newegg, Costco and most others) and PricePing checks the price in the background and pings you when it drops. Add the same product from several stores and it shows which one is cheapest.

- **No server, no account, no upkeep.** Everything runs on the phone. Data is one small file on the device.
- **Works with almost any store.** It reads the product data stores publish for Google Shopping (schema.org), plus store-specific fallbacks for Amazon, Walmart, Target, Best Buy, Newegg and eBay.
- **Price history chart**, lowest-seen price, target-price alerts, back-in-stock alerts.
- **You choose how often it checks**: hourly, every 3/6/12 hours, daily, or manual only.

## Get the APK

The `.github/workflows/build.yml` workflow builds the APK for free on GitHub and publishes it on the **Releases** page. The latest version is always at:

```
https://github.com/<your-username>/<repo-name>/releases/latest/download/PricePing.apk
```

Open that on an Android phone (Android 8 or newer) and install. Android asks once to allow installs from your browser.

### Optional: keep the same signing key

Add two repository secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`. Then every build is signed with the same key and new versions install over old ones. Without them, the app still builds and installs, but updating means uninstalling first (use Settings → Export backup, then Import after reinstalling).

## Limits worth knowing

- Stores can block automated checks, Amazon most often. Blocked checks show a note and retry on the next run.
- Android decides the exact moment background jobs run, so "every 6 hours" may drift a bit to save battery.
- PricePing compares the links you add; it doesn't search other stores on its own.

## Build it yourself

Open the folder in Android Studio and press Run, or with Gradle 8.11 and JDK 17: `gradle assembleRelease`.
