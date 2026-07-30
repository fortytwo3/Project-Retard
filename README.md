# paladex·ex

Point your phone at a Pokémon card and get what it's actually worth — TCGplayer,
Cardmarket, PriceCharting, and recent eBay sold listings, side by side.

Mobile-first web app. No app store, no install: open it in a browser, allow the
camera, scan.

---

## How it works

```
camera frame
   └─ crop to the card guide          (CameraCapture.tsx)
      └─ OCR the title + footer bands (lib/ocr/client.ts, on-device)
         └─ parse name + number       (lib/ocr/parse.ts)
            └─ rank candidate cards   (lib/pokemontcg.ts)
               └─ you pick the printing
                  └─ fan out to every price source  (lib/providers/)
                     └─ one headline number + the full breakdown
```

The photo never leaves your device — OCR runs in the browser via Tesseract, and
only the handful of characters it extracts get sent to the server.

The scan is a **ranking** problem, not a lookup. One name and number can belong
to several printings worth wildly different amounts, so the app shows you the
matches and lets you pick rather than guessing.

## Price sources

| Source | Needs | Falls back to |
|---|---|---|
| **TCGplayer** | nothing | — (free via pokemontcg.io) |
| **Cardmarket** | nothing | — (free via pokemontcg.io) |
| **PriceCharting** | `PRICECHARTING_TOKEN` (~$10/mo) | scraping the public product page |
| **eBay sold** | Marketplace Insights approval | scraping the sold-listings page |
| **eBay active** | `EBAY_CLIENT_ID` + secret | — |

**With an empty config file, every source still works** — the free API covers
TCGplayer and Cardmarket, and the other two are scraped. Adding keys upgrades
each source to its official API in place. The `/settings` page shows which mode
each source is currently in and why.

### The headline number

Completed sales beat asking prices, so eBay leads when it has a usable sample:

- **5+ recent sales** → the outlier-trimmed average of those sales
- **1–4 sales** → blended with TCGplayer's market price, since a thin sample is jumpy
- **no sales** → TCGplayer market → PriceCharting ungraded → eBay asking → Cardmarket trend
- **nothing at all** → says so, rather than inventing a number

Every eBay sample is filtered for relevance (bulk lots, "you pick" listings, and
proxies are dropped) and then trimmed at the 1.5×IQR fences. This matters more
than it sounds: a search for a $20 card reliably surfaces one $4,000 graded copy,
and a plain mean lands somewhere nobody would ever trade at.

## Running it

```bash
npm install
cp .env.example .env.local   # optional — it runs fine empty
npm run dev
```

Open http://localhost:3000.

**The camera needs HTTPS.** `localhost` is exempt, so desktop works out of the
box, but to scan with your phone you need a real certificate — deploy it, or
tunnel with something like `ngrok http 3000`. Without HTTPS the app falls back to
the upload button and name search, both of which work fine.

```bash
npm test         # 91 tests, no network required
npm run build
npm run typecheck
```

## Scan tips

The collector number in the bottom corner is the single most valuable thing on
the card — it survives OCR better than the name and carries most of the matching
weight. If a scan keeps failing:

- Kill the glare. Foil cards are the hard case; light from the side, not head on.
- Fill the guide box with the card.
- Take the card out of the sleeve.
- Or just tap **Search by name** — it's a first-class path, not a consolation prize.

## Configuration

Every key is optional. See [`.env.example`](.env.example) for the full list.

| Variable | Effect |
|---|---|
| `POKEMONTCG_API_KEY` | Raises the free tier from ~1k to 20k requests/day |
| `PRICECHARTING_TOKEN` | Uses the paid API instead of scraping |
| `EBAY_CLIENT_ID` / `EBAY_CLIENT_SECRET` | Enables the Browse API |
| `EBAY_MARKETPLACE_INSIGHTS_ENABLED` | Set once eBay approves Insights access |
| `SCRAPE_ENABLED` | `false` restricts the app to official APIs only |
| `SCRAPE_MIN_INTERVAL_MS` | Floor on the gap between requests to one host |
| `CACHE_TTL_SECONDS` | Provider cache lifetime; `0` disables caching |

### About the scrapers

When a source has no key configured, the app reads the same public page you
would open yourself. Requests are serialised per host with a configurable
minimum gap, cached to disk, and sent with a real User-Agent. Scraped numbers
are labelled `scraped` in every report so you always know what you're looking at.

Two things worth knowing:

- **Both sites restrict automated access in their terms of service.** This is
  fine for looking up your own cards; it is not a foundation for anything you
  run at volume or commercially. `SCRAPE_ENABLED=false` turns it off.
- **Markup changes break scrapers.** eBay in particular A/B tests its result
  cards. The parser handles the layouts known as of writing and degrades to an
  empty result rather than a wrong one — if a source suddenly reports nothing,
  that's the first thing to check.

## Layout

```
src/
├── app/
│   ├── page.tsx              scan → identify → price, as a state machine
│   ├── history/              localStorage scan log + running total
│   ├── settings/             which sources are live, and why
│   └── api/
│       ├── identify/         OCR text → ranked card candidates
│       ├── search/           name search, backing manual entry
│       ├── prices/           card id → full price report
│       └── capabilities/     config status (never leaks key values)
├── components/               camera, candidate grid, report, sold listings
└── lib/
    ├── ocr/                  browser OCR + the text parser
    ├── providers/            one adapter per price source
    ├── pokemontcg.ts         card lookup + fuzzy matching
    ├── stats.ts              outlier trimming, medians, trend
    ├── http.ts               per-host throttle, retry, timeout
    └── cache.ts              two-tier memory + disk cache
```

## Tests

`npm test` runs 91 tests with no network access. Beyond the unit tests on the
parsers and statistics, `tests/pipeline.test.ts` drives the whole server path —
OCR text in, price report out — against fixture HTML and JSON, including the
cases that matter operationally: a source going down mid-report, eBay serving a
bot-check page, and a bulk lot polluting the sales sample.

## Deploying

Vercel is the path of least resistance — push, import, add any env vars, done.
The price route is marked `maxDuration = 60` because a cold cache means several
throttled scrapes; on a hobby plan you may want `SCRAPE_ENABLED=false` and real
API keys instead.

## Limits

- **Scanning is name + number matching, not image recognition.** It won't tell a
  1st Edition from an Unlimited print, or spot a fake. Pick the right printing
  from the candidate list.
- **Grade is read from eBay titles**, not from the card. The app can't grade your
  copy; PriceCharting's grade rows are the reference for what a graded one goes for.
- **No currency conversion.** Cardmarket reports in EUR and stays in EUR; the
  history total only sums entries sharing one currency.
- **Prices are estimates.** Every number links back to its source — check them
  before anything expensive changes hands.
