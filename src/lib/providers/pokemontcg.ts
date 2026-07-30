import { getCardById } from "../pokemontcg";
import type { Card, PricePoint, ProviderResult } from "../types";
import { errored, makeResult, type PriceProvider } from "./types";

/**
 * TCGplayer and Cardmarket prices, both of which pokemontcg.io republishes for
 * free. This is the only source that needs no key and no scraping, so it is the
 * baseline every scan gets.
 */

interface TcgPriceBlock {
  low?: number | null;
  mid?: number | null;
  high?: number | null;
  market?: number | null;
  directLow?: number | null;
}

/** pokemontcg.io keys TCGplayer prices by printing; these are the ones seen in the wild. */
const TCG_VARIANT_LABELS: Record<string, string> = {
  normal: "Normal",
  holofoil: "Holofoil",
  reverseHolofoil: "Reverse Holofoil",
  "1stEditionHolofoil": "1st Edition Holofoil",
  "1stEditionNormal": "1st Edition Normal",
  unlimitedHolofoil: "Unlimited Holofoil",
  unlimited: "Unlimited",
};

function tcgplayerPrices(raw: unknown): PricePoint[] {
  if (!raw || typeof raw !== "object") return [];

  const points: PricePoint[] = [];

  for (const [variant, block] of Object.entries(raw as Record<string, TcgPriceBlock>)) {
    if (!block || typeof block !== "object") continue;

    const label = TCG_VARIANT_LABELS[variant] ?? variant;

    // `market` is TCGplayer's own estimate of what the card actually trades at,
    // so it leads. `low` is shown alongside because it is what you would pay
    // today if you bought the cheapest listed copy.
    if (typeof block.market === "number") {
      points.push({ label: `${label} — Market`, amount: block.market, currency: "USD" });
    }
    if (typeof block.low === "number") {
      points.push({ label: `${label} — Low`, amount: block.low, currency: "USD" });
    }
    if (typeof block.directLow === "number") {
      points.push({
        label: `${label} — Direct Low`,
        amount: block.directLow,
        currency: "USD",
        note: "TCGplayer Direct",
      });
    }
  }

  return points;
}

interface CardmarketPrices {
  averageSellPrice?: number;
  lowPrice?: number;
  trendPrice?: number;
  avg1?: number;
  avg7?: number;
  avg30?: number;
  reverseHoloTrend?: number;
  reverseHoloAvg30?: number;
}

function cardmarketPrices(raw: unknown): PricePoint[] {
  if (!raw || typeof raw !== "object") return [];
  const p = raw as CardmarketPrices;

  const rows: Array<[string, number | undefined, string?]> = [
    ["Trend Price", p.trendPrice],
    ["Average Sell Price", p.averageSellPrice],
    ["Lowest Listing", p.lowPrice],
    ["30-day Average", p.avg30, "Rolling 30 days"],
    ["7-day Average", p.avg7, "Rolling 7 days"],
    ["1-day Average", p.avg1, "Yesterday"],
    ["Reverse Holo Trend", p.reverseHoloTrend],
  ];

  return rows
    .filter((row): row is [string, number, string?] => typeof row[1] === "number")
    .map(([label, amount, note]) => ({ label, amount, currency: "EUR" as const, note }));
}

export const pokemontcgProvider: PriceProvider = {
  id: "pokemontcg",
  label: "pokemontcg.io",

  async fetch(card: Card): Promise<ProviderResult[]> {
    try {
      // The card handed to us may have come from a long-lived metadata cache,
      // so re-read it on a short TTL to pick up moved prices.
      const fresh = (await getCardById(card.id)) ?? card;

      const tcg = tcgplayerPrices(fresh.rawTcgplayer);
      const cm = cardmarketPrices(fresh.rawCardmarket);

      return [
        makeResult("tcgplayer", "TCGplayer", tcg.length > 0 ? "ok" : "empty", "api", {
          prices: tcg,
          sourceUrl: fresh.tcgplayerUrl,
          message: tcg.length === 0 ? "No TCGplayer pricing published for this card." : undefined,
        }),
        makeResult("cardmarket", "Cardmarket", cm.length > 0 ? "ok" : "empty", "api", {
          prices: cm,
          sourceUrl: fresh.cardmarketUrl,
          message: cm.length === 0 ? "No Cardmarket pricing published for this card." : undefined,
        }),
      ];
    } catch (error) {
      return [
        errored("tcgplayer", "TCGplayer", "api", error),
        errored("cardmarket", "Cardmarket", "api", error),
      ];
    }
  },
};
