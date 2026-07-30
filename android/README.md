# paladex·ex — Android

Native Android build of the card scanner. Kotlin, Jetpack Compose, CameraX, and
ML Kit for on-device OCR.

No server. The phone talks to pokemontcg.io, PriceCharting and eBay directly,
which removes the web version's two worst problems: nothing to deploy, and
scraping runs from a residential mobile IP rather than a datacenter one that
eBay bot-checks.

---

## ⚠️ Build status — read this first

The two modules were verified very differently:

| Module | What it is | Verified? |
|---|---|---|
| **`core/`** | All the logic: OCR text parsing, card matching, statistics, all four price providers | ✅ **Compiles, 96 tests pass** |
| **`app/`** | Compose UI, CameraX, ML Kit, storage | ❌ **Never compiled** |

`app/` was written without ever being built, because the machine it was written
on had no Android SDK and no route to `dl.google.com`, so AndroidX, CameraX, ML
Kit and the Android Gradle Plugin could not be resolved.

**Expect to fix things when you first open it in Android Studio.** Import
mismatches and API-signature drift are the likely failures. What it is *not*
likely to be is wrong about the logic, because the logic lives in `core/` and
that is tested. Two Android-specific bugs were caught by inspection and already
fixed (a `BlendMode.Clear` overlay that would have rendered as a black box over
the viewfinder, and an `ImageProxy.toBitmap` extension silently shadowed by a
CameraX member, which would have dropped the rotation fix).

## Getting it running

```bash
# From the repository root
cd android
# Open this directory in Android Studio (Ladybug or newer), let it sync,
# then Run. Android Studio writes local.properties with your SDK path.
```

To run just the tested part, with no Android SDK at all:

```bash
./gradlew :core:test
```

`settings.gradle.kts` includes `:app` only when an SDK is present, so the core
suite runs on a bare JDK 17+ — useful in CI, and how it was verified.

## Architecture

```
core/                      pure Kotlin/JVM, no Android dependencies
├── CardTextParser.kt      OCR text  →  name, collector number, set code
├── PokemonTcgClient.kt    card lookup + fuzzy ranking of candidates
├── Stats.kt               outlier trimming, medians, trend
├── Money.kt               price-string parsing, grade detection
├── PriceService.kt        provider fan-out + the consensus number
├── net/                   per-host throttle, retry, TTL cache
└── providers/             one adapter per price source

app/                       Android
├── ocr/CardRecogniser     ML Kit, cropped to the title and footer bands
├── ui/CameraCapture       CameraX preview + card guide + crop
├── ui/ScanScreen          scan → candidates → report, as a state machine
├── ui/ReportScreen        price breakdown per source
├── data/Stores            history + API keys in SharedPreferences
└── ScanViewModel          the whole flow, as StateFlow
```

The split is deliberate: everything that can be got wrong quietly — a regex, an
outlier fence, an HTML selector — lives in `core/`, where it is tested. The
Android module is camera plumbing and views over it.

## Price sources

| Source | With a key | Without |
|---|---|---|
| TCGplayer + Cardmarket | free via pokemontcg.io | same |
| PriceCharting | paid API token | reads the public product page |
| eBay sold | Marketplace Insights API | reads the sold-listings page |
| eBay active | client ID + secret | — |

Every key is optional and set in-app under **Sources**, which also shows which
mode each source is currently in and why. With nothing configured, all four
still return data.

### The headline number

Completed sales beat asking prices, so eBay leads when it has a usable sample:

- **5+ recent sales** → outlier-trimmed average of those sales
- **1–4 sales** → blended with TCGplayer market, since a thin sample is jumpy
- **no sales** → TCGplayer market → PriceCharting ungraded → eBay asking → Cardmarket trend
- **nothing** → says so, rather than inventing a number

eBay samples are filtered for relevance (bulk lots, "you pick", proxies) and
trimmed at the 1.5×IQR fences. A search for a $20 card reliably surfaces one
$4,000 graded copy; an untrimmed mean lands where nobody would trade.

## Scanning

The camera frame is cropped to the on-screen guide, then ML Kit reads two bands
— the name at the top and the collector number at the bottom. Reading the whole
card wastes time on attack text and gives the name heuristic dozens of extra
lines to be wrong about.

Preview and capture are both pinned to 4:3 with the preview letterboxed, which
is what makes the crop a simple centred fraction. If they differed, the guide
box would not correspond to a fixed region of the captured frame.

The collector number is the most valuable thing on the card — it survives OCR
better than the name and carries most of the matching weight. If a scan keeps
failing: kill the glare, fill the guide, take the card out of the sleeve, or
just search by name.

## Requirements

- Android Studio Ladybug (2024.2) or newer
- **minSdk 26** — `core/` uses `java.time` and `java.util.Base64` directly.
  Lowering it means enabling core library desugaring.
- JDK 17+

## Tests

```bash
./gradlew :core:test     # 96 tests, no network, no Android SDK
```

Beyond unit tests on the parsers and statistics, `PipelineTest` drives the whole
path — OCR text in, price report out — against a `MockWebServer`, with the
providers keeping their real production URLs via a host-rewriting interceptor.
It covers a source going down mid-report, eBay serving a bot-check page, and a
bulk lot polluting the sales sample.

## Limits

- **No live source has ever been called.** The build machine's network policy
  blocked `api.pokemontcg.io`; all 96 tests use fixtures. The eBay and
  PriceCharting scrapers are a reconstruction of those pages' markup — check
  them first if a source returns nothing.
- **Name + number matching, not image recognition.** It cannot tell a 1st
  Edition from an Unlimited print, or spot a fake. Pick the printing yourself.
- **Grade is read from eBay titles**, not from your card.
- **No currency conversion.** Cardmarket stays in EUR; the history total only
  sums entries sharing one currency.
- **API keys sit in SharedPreferences**, private to the app. Fine for personal
  marketplace keys; not where you would put anything that moves money.
