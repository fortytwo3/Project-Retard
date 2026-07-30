import { describeCapabilities } from "../env";
import { trimmedMean } from "../stats";
import type { Card, PriceReport, ProviderResult } from "../types";
import { ebayProvider } from "./ebay";
import { pokemontcgProvider } from "./pokemontcg";
import { priceChartingProvider } from "./pricecharting";
import { errored, type PriceProvider } from "./types";

const PROVIDERS: PriceProvider[] = [pokemontcgProvider, priceChartingProvider, ebayProvider];

/** Order the cards appear in the UI, most-trusted first. */
const DISPLAY_ORDER = ["ebay", "tcgplayer", "pricecharting", "cardmarket"];

function sortResults(results: ProviderResult[]): ProviderResult[] {
  return [...results].sort((a, b) => {
    // Sources with data always outrank sources without.
    const rank = (r: ProviderResult) => (r.status === "ok" ? 0 : r.status === "empty" ? 1 : 2);
    if (rank(a) !== rank(b)) return rank(a) - rank(b);

    const ai = DISPLAY_ORDER.indexOf(a.providerId);
    const bi = DISPLAY_ORDER.indexOf(b.providerId);
    return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi);
  });
}

function findPrice(results: ProviderResult[], providerId: string, match: RegExp): number | null {
  const provider = results.find((r) => r.providerId === providerId && r.status === "ok");
  if (!provider) return null;

  const point = provider.prices.find((p) => match.test(p.label) && p.amount !== null);
  return point?.amount ?? null;
}

/**
 * The single headline number.
 *
 * Completed sales beat listed prices, so eBay leads when it has a usable
 * sample. Below five sales the average is too jumpy to trust on its own, and we
 * blend it with TCGplayer's market price instead. Everything below that is a
 * fallback chain through the remaining USD sources — Cardmarket is quoted in
 * euros and is deliberately last, used only when nothing else reported.
 */
function computeConsensus(results: ProviderResult[]): PriceReport["consensus"] {
  const ebay = results.find((r) => r.providerId === "ebay" && r.status === "ok");
  const soldSample = ebay?.soldSummary;
  const isRealSold = ebay?.label.includes("Sold") ?? false;

  const tcgMarket = findPrice(results, "tcgplayer", /Market/);

  if (soldSample && isRealSold && soldSample.count >= 5) {
    return {
      amount: soldSample.trimmedMean,
      currency: soldSample.currency,
      basis: `Average of ${soldSample.count} recent eBay sales`,
    };
  }

  if (soldSample && isRealSold && tcgMarket !== null) {
    return {
      amount: trimmedMean([soldSample.trimmedMean, tcgMarket]),
      currency: "USD",
      basis: `Blend of ${soldSample.count} eBay ${soldSample.count === 1 ? "sale" : "sales"} and TCGplayer market price`,
    };
  }

  if (soldSample && isRealSold) {
    return {
      amount: soldSample.trimmedMean,
      currency: soldSample.currency,
      basis: `Only ${soldSample.count} recent eBay ${soldSample.count === 1 ? "sale" : "sales"} — treat as rough`,
    };
  }

  if (tcgMarket !== null) {
    return { amount: tcgMarket, currency: "USD", basis: "TCGplayer market price" };
  }

  const ungraded = findPrice(results, "pricecharting", /Ungraded/);
  if (ungraded !== null) {
    return { amount: ungraded, currency: "USD", basis: "PriceCharting ungraded" };
  }

  const asking = ebay?.soldSummary;
  if (asking) {
    return {
      amount: asking.trimmedMean,
      currency: asking.currency,
      basis: "Average eBay asking price — no completed sales available",
    };
  }

  const cardmarketTrend = findPrice(results, "cardmarket", /Trend/);
  if (cardmarketTrend !== null) {
    return { amount: cardmarketTrend, currency: "EUR", basis: "Cardmarket trend price" };
  }

  return { amount: null, currency: "USD", basis: "No source returned a price for this card" };
}

/**
 * Query every provider for one card. Providers run concurrently and are
 * individually fault-isolated — one site being down or having changed its
 * markup must never take the whole report with it.
 */
export async function buildPriceReport(card: Card): Promise<PriceReport> {
  const settled = await Promise.allSettled(PROVIDERS.map((provider) => provider.fetch(card)));

  const results: ProviderResult[] = [];

  settled.forEach((outcome, index) => {
    if (outcome.status === "fulfilled") {
      results.push(...outcome.value);
    } else {
      const provider = PROVIDERS[index];
      results.push(errored(provider.id, provider.label, "none", outcome.reason));
    }
  });

  return {
    card,
    results: sortResults(results),
    consensus: computeConsensus(results),
    generatedAt: new Date().toISOString(),
  };
}

export { describeCapabilities };
export const __testing = { computeConsensus, sortResults };
