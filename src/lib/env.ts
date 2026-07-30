/**
 * Server-side configuration. Read once at module load so every provider sees a
 * consistent view, and so a missing key is a capability question rather than an
 * exception thrown deep inside a fetch.
 */

function str(name: string, fallback = ""): string {
  return (process.env[name] ?? fallback).trim();
}

function bool(name: string, fallback: boolean): boolean {
  const raw = str(name);
  if (!raw) return fallback;
  return raw.toLowerCase() === "true" || raw === "1";
}

function int(name: string, fallback: number): number {
  const parsed = Number.parseInt(str(name), 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export const env = {
  pokemontcgApiKey: str("POKEMONTCG_API_KEY"),

  priceChartingToken: str("PRICECHARTING_TOKEN"),

  ebayClientId: str("EBAY_CLIENT_ID"),
  ebayClientSecret: str("EBAY_CLIENT_SECRET"),
  ebayMarketplaceInsights: bool("EBAY_MARKETPLACE_INSIGHTS_ENABLED", false),
  ebayMarketplaceId: str("EBAY_MARKETPLACE_ID", "EBAY_US"),

  scrapeEnabled: bool("SCRAPE_ENABLED", true),
  scrapeMinIntervalMs: int("SCRAPE_MIN_INTERVAL_MS", 1500),

  cacheTtlSeconds: int("CACHE_TTL_SECONDS", 3600),
} as const;

/** Whether eBay's official APIs can be called at all. */
export function hasEbayCredentials(): boolean {
  return Boolean(env.ebayClientId && env.ebayClientSecret);
}

/**
 * Which strategy each provider will use, given the current config. Rendered on
 * the settings screen so it is obvious why a source is missing.
 */
export function describeCapabilities() {
  return [
    {
      id: "pokemontcg",
      label: "TCGplayer + Cardmarket (via pokemontcg.io)",
      method: "api" as const,
      active: true,
      detail: env.pokemontcgApiKey
        ? "Using your API key (20k requests/day)."
        : "Using the free anonymous tier (~1k requests/day). Set POKEMONTCG_API_KEY to raise it.",
    },
    {
      id: "pricecharting",
      label: "PriceCharting",
      method: env.priceChartingToken ? ("api" as const) : ("scrape" as const),
      active: Boolean(env.priceChartingToken) || env.scrapeEnabled,
      detail: env.priceChartingToken
        ? "Using the paid API token."
        : env.scrapeEnabled
          ? "No token set — scraping the public product page instead."
          : "No token, and scraping is disabled. This source is off.",
    },
    {
      id: "ebay",
      label: "eBay recently sold",
      method:
        hasEbayCredentials() && env.ebayMarketplaceInsights
          ? ("api" as const)
          : env.scrapeEnabled
            ? ("scrape" as const)
            : ("none" as const),
      active: (hasEbayCredentials() && env.ebayMarketplaceInsights) || env.scrapeEnabled,
      detail:
        hasEbayCredentials() && env.ebayMarketplaceInsights
          ? "Using the Marketplace Insights API for completed sales."
          : hasEbayCredentials()
            ? "Credentials found but Marketplace Insights is not enabled, so sold data comes from scraping. Browse API supplies active listings."
            : env.scrapeEnabled
              ? "No eBay credentials — scraping the sold-listings search page."
              : "No credentials, and scraping is disabled. This source is off.",
    },
  ];
}
