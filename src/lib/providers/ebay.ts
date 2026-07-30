import { cached } from "../cache";
import { env, hasEbayCredentials } from "../env";
import { fetchHtml, fetchJson, politeFetch } from "../http";
import { detectGrade } from "../money";
import { summariseSales } from "../stats";
import type { Card, Currency, ProviderResult, SoldListing } from "../types";
import { parseSoldListings, soldSearchUrl } from "./ebay-scrape";
import { disabled, errored, makeResult, marketplaceQuery, type PriceProvider } from "./types";

/**
 * eBay completed sales — what the card actually sold for, which is the number
 * that matters most and the one every other source is only estimating.
 *
 * Three strategies, in descending order of fidelity:
 *   1. Marketplace Insights API — real completed sales, but eBay gates access
 *      behind a separate business application.
 *   2. Browse API — active listings only. Asking prices, clearly labelled.
 *   3. Scraping the sold-listings search page — what you would look at yourself.
 */

const OAUTH_URL = "https://api.ebay.com/identity/v1/oauth2/token";
const INSIGHTS_URL = "https://api.ebay.com/buy/marketplace_insights/v1_beta/item_sales/search";
const BROWSE_URL = "https://api.ebay.com/buy/browse/v1/item_summary/search";

/** Cached client-credentials token, refreshed a minute before it lapses. */
let tokenCache: { token: string; expiresAt: number } | null = null;

async function getAccessToken(scope: string): Promise<string> {
  if (tokenCache && tokenCache.expiresAt > Date.now()) return tokenCache.token;

  const basic = Buffer.from(`${env.ebayClientId}:${env.ebayClientSecret}`).toString("base64");

  const response = await politeFetch(OAUTH_URL, {
    method: "POST",
    immediate: true,
    headers: {
      Authorization: `Basic ${basic}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({ grant_type: "client_credentials", scope }).toString(),
  });

  const json = (await response.json()) as { access_token: string; expires_in: number };
  tokenCache = {
    token: json.access_token,
    expiresAt: Date.now() + (json.expires_in - 60) * 1000,
  };
  return json.access_token;
}

interface InsightsSale {
  title: string;
  lastSoldPrice?: { value: string; currency: string };
  lastSoldDate?: string;
  itemWebUrl?: string;
  image?: { imageUrl?: string };
}

async function viaInsightsApi(card: Card, query: string): Promise<SoldListing[]> {
  const token = await getAccessToken("https://api.ebay.com/oauth/api_scope/buy.marketplace.insights");

  // Marketplace Insights caps the lookback at 90 days.
  const since = new Date(Date.now() - 90 * 86_400_000).toISOString();
  const url =
    `${INSIGHTS_URL}?q=${encodeURIComponent(query)}&limit=100` +
    `&filter=${encodeURIComponent(`lastSoldDate:[${since}..]`)}`;

  const json = await fetchJson<{ itemSales?: InsightsSale[] }>(url, {
    immediate: true,
    headers: {
      Authorization: `Bearer ${token}`,
      "X-EBAY-C-MARKETPLACE-ID": env.ebayMarketplaceId,
    },
  });

  return (json.itemSales ?? [])
    .map((sale): SoldListing | null => {
      const amount = Number.parseFloat(sale.lastSoldPrice?.value ?? "");
      if (!Number.isFinite(amount)) return null;

      return {
        title: sale.title,
        price: amount,
        currency: (sale.lastSoldPrice?.currency ?? "USD") as Currency,
        soldAt: sale.lastSoldDate ?? null,
        url: sale.itemWebUrl ?? null,
        imageUrl: sale.image?.imageUrl ?? null,
        shippingIncluded: false,
        detectedGrade: detectGrade(sale.title),
      };
    })
    .filter((l): l is SoldListing => l !== null);
}

interface BrowseItem {
  title: string;
  price?: { value: string; currency: string };
  itemWebUrl?: string;
  image?: { imageUrl?: string };
}

async function viaBrowseApi(query: string): Promise<SoldListing[]> {
  const token = await getAccessToken("https://api.ebay.com/oauth/api_scope");
  const url = `${BROWSE_URL}?q=${encodeURIComponent(query)}&limit=50`;

  const json = await fetchJson<{ itemSummaries?: BrowseItem[] }>(url, {
    immediate: true,
    headers: {
      Authorization: `Bearer ${token}`,
      "X-EBAY-C-MARKETPLACE-ID": env.ebayMarketplaceId,
    },
  });

  return (json.itemSummaries ?? [])
    .map((item): SoldListing | null => {
      const amount = Number.parseFloat(item.price?.value ?? "");
      if (!Number.isFinite(amount)) return null;

      return {
        title: item.title,
        price: amount,
        currency: (item.price?.currency ?? "USD") as Currency,
        soldAt: null,
        url: item.itemWebUrl ?? null,
        imageUrl: item.image?.imageUrl ?? null,
        shippingIncluded: false,
        detectedGrade: detectGrade(item.title),
      };
    })
    .filter((l): l is SoldListing => l !== null);
}

/**
 * Drop listings that matched the search but are not the card.
 *
 * Sellers pack titles with keywords, so a search for one card returns lots,
 * proxies, and "choose your card" listings. Requiring the card's own name and
 * rejecting the obvious bulk-listing vocabulary removes most of them, and the
 * statistical outlier trim in `summariseSales` handles the rest.
 */
function isPlausibleMatch(listing: SoldListing, card: Card): boolean {
  const title = listing.title.toLowerCase();
  const name = card.name.toLowerCase().replace(/\s+(ex|gx|v|vmax|vstar)$/i, "").trim();

  if (name && !title.includes(name)) return false;

  return !/\b(lot|bundle|choose|pick|you pick|proxy|custom|repack|mystery|playset|complete set)\b/i.test(
    listing.title,
  );
}

async function viaScrape(query: string): Promise<{ listings: SoldListing[]; url: string }> {
  const url = soldSearchUrl(query);
  const html = await fetchHtml(url);
  return { listings: parseSoldListings(html), url };
}

export const ebayProvider: PriceProvider = {
  id: "ebay",
  label: "eBay",

  async fetch(card: Card): Promise<ProviderResult[]> {
    const query = marketplaceQuery(card);
    const browseUrl = soldSearchUrl(query);

    // 1. Real completed sales, if eBay has granted Insights access.
    if (hasEbayCredentials() && env.ebayMarketplaceInsights) {
      try {
        const { value, cached: fromCache } = await cached(`ebay:insights:${card.id}`, () =>
          viaInsightsApi(card, query),
        );
        const listings = value.filter((l) => isPlausibleMatch(l, card));

        if (listings.length > 0) {
          const summary = summariseSales(listings);
          return [
            makeResult("ebay", "eBay — Recently Sold", "ok", "api", {
              sourceUrl: browseUrl,
              soldListings: listings,
              soldSummary: summary,
              cached: fromCache,
              prices: summary
                ? [
                    {
                      label: "Sold average",
                      amount: summary.trimmedMean,
                      currency: summary.currency,
                      note: `${summary.count} sales, outliers removed`,
                    },
                    {
                      label: "Sold median",
                      amount: summary.median,
                      currency: summary.currency,
                    },
                  ]
                : [],
            }),
          ];
        }
      } catch (error) {
        if (!env.scrapeEnabled) {
          return [errored("ebay", "eBay — Recently Sold", "api", error, browseUrl)];
        }
      }
    }

    // 2. Scraping the sold-listings page. This is the default path, and the
    //    only one that returns completed sales without a business agreement.
    if (env.scrapeEnabled) {
      try {
        const { value, cached: fromCache } = await cached(`ebay:scrape:${card.id}`, () =>
          viaScrape(query),
        );
        const listings = value.listings.filter((l) => isPlausibleMatch(l, card));

        if (listings.length > 0) {
          const summary = summariseSales(listings);
          return [
            makeResult("ebay", "eBay — Recently Sold", "ok", "scrape", {
              sourceUrl: value.url,
              soldListings: listings,
              soldSummary: summary,
              cached: fromCache,
              prices: summary
                ? [
                    {
                      label: "Sold average",
                      amount: summary.trimmedMean,
                      currency: summary.currency,
                      note: `${summary.count} sales, outliers removed`,
                    },
                    {
                      label: "Sold median",
                      amount: summary.median,
                      currency: summary.currency,
                    },
                  ]
                : [],
            }),
          ];
        }

        // Fall through to Browse only if we have credentials; otherwise report
        // the empty result honestly rather than inventing a number.
        if (!hasEbayCredentials()) {
          return [
            makeResult("ebay", "eBay — Recently Sold", "empty", "scrape", {
              sourceUrl: value.url,
              message:
                value.listings.length > 0
                  ? "Found sold listings but none matched this exact card."
                  : "No recent sold listings found. eBay may also have served a bot-check page.",
              cached: fromCache,
            }),
          ];
        }
      } catch (error) {
        if (!hasEbayCredentials()) {
          return [errored("ebay", "eBay — Recently Sold", "scrape", error, browseUrl)];
        }
      }
    }

    // 3. Active listings. Asking prices, not sales — labelled as such.
    if (hasEbayCredentials()) {
      try {
        const { value, cached: fromCache } = await cached(`ebay:browse:${card.id}`, () =>
          viaBrowseApi(query),
        );
        const listings = value.filter((l) => isPlausibleMatch(l, card));
        const summary = summariseSales(listings);

        return [
          makeResult("ebay", "eBay — Active Listings", listings.length > 0 ? "ok" : "empty", "api", {
            sourceUrl: browseUrl,
            soldListings: listings,
            soldSummary: summary,
            cached: fromCache,
            message:
              "Showing active asking prices — completed sales need Marketplace Insights access.",
            prices: summary
              ? [
                  {
                    label: "Asking average",
                    amount: summary.trimmedMean,
                    currency: summary.currency,
                    note: `${summary.count} active listings`,
                  },
                ]
              : [],
          }),
        ];
      } catch (error) {
        return [errored("ebay", "eBay", "api", error, browseUrl)];
      }
    }

    return [
      disabled(
        "ebay",
        "eBay — Recently Sold",
        "Set SCRAPE_ENABLED=true, or add EBAY_CLIENT_ID and EBAY_CLIENT_SECRET.",
      ),
    ];
  },
};

export const __testing = { isPlausibleMatch };
