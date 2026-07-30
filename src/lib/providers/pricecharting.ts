import * as cheerio from "cheerio";

import { cached } from "../cache";
import { env } from "../env";
import { fetchHtml, fetchJson } from "../http";
import { parseMoney } from "../money";
import type { Card, PricePoint, ProviderResult } from "../types";
import { disabled, errored, makeResult, type PriceProvider } from "./types";

/**
 * PriceCharting, via the paid API when a token is configured and by reading the
 * public product page when it is not.
 *
 * PriceCharting stores trading cards in the same six price columns it uses for
 * video games, which is why the field names read oddly. The mapping below is
 * fixed and applies to every card product.
 */
const GRADE_LABELS = {
  loose: "Ungraded",
  cib: "Grade 7",
  new: "Grade 8",
  graded: "Grade 9",
  boxOnly: "Grade 9.5",
  manualOnly: "PSA 10",
} as const;

const BASE = "https://www.pricecharting.com";

/** API amounts arrive in whole cents. */
function centsToDollars(value: unknown): number | null {
  return typeof value === "number" && value > 0 ? value / 100 : null;
}

interface ApiProduct {
  status?: string;
  id?: string;
  "product-name"?: string;
  "console-name"?: string;
  "loose-price"?: number;
  "cib-price"?: number;
  "new-price"?: number;
  "graded-price"?: number;
  "box-only-price"?: number;
  "manual-only-price"?: number;
}

/**
 * The query PriceCharting matches best. Its index is keyed on card name plus
 * set name; feeding it the collector number as well tends to return nothing.
 */
function searchTerm(card: Card): string {
  return `${card.name} ${card.setName} ${card.number}`.replace(/\s+/g, " ").trim();
}

async function viaApi(card: Card): Promise<ProviderResult> {
  const url = `${BASE}/api/product?t=${encodeURIComponent(env.priceChartingToken)}&q=${encodeURIComponent(searchTerm(card))}`;

  const { value: product, cached: fromCache } = await cached(`pc:api:${card.id}`, () =>
    fetchJson<ApiProduct>(url, { immediate: true }),
  );

  if (product.status && product.status !== "success") {
    return makeResult("pricecharting", "PriceCharting", "empty", "api", {
      message: `PriceCharting has no product matching "${searchTerm(card)}".`,
      cached: fromCache,
    });
  }

  const prices: PricePoint[] = (
    [
      [GRADE_LABELS.loose, centsToDollars(product["loose-price"])],
      [GRADE_LABELS.cib, centsToDollars(product["cib-price"])],
      [GRADE_LABELS.new, centsToDollars(product["new-price"])],
      [GRADE_LABELS.graded, centsToDollars(product["graded-price"])],
      [GRADE_LABELS.boxOnly, centsToDollars(product["box-only-price"])],
      [GRADE_LABELS.manualOnly, centsToDollars(product["manual-only-price"])],
    ] as Array<[string, number | null]>
  )
    .filter(([, amount]) => amount !== null)
    .map(([label, amount]) => ({ label, amount, currency: "USD" as const }));

  return makeResult("pricecharting", "PriceCharting", prices.length > 0 ? "ok" : "empty", "api", {
    prices,
    sourceUrl: product.id ? `${BASE}/game/${product.id}` : null,
    cached: fromCache,
    message:
      prices.length === 0 ? "PriceCharting returned a product but no prices for it." : undefined,
  });
}

/** Find the product page URL for a card by running PriceCharting's own search. */
async function findProductUrl(card: Card): Promise<string | null> {
  const searchUrl = `${BASE}/search-products?q=${encodeURIComponent(searchTerm(card))}&type=prices`;
  const html = await fetchHtml(searchUrl);
  const $ = cheerio.load(html);

  // A search with exactly one hit redirects straight to the product page, in
  // which case the price table is already in front of us.
  if ($("#full_price_guide, #price_data").length > 0) {
    return searchUrl;
  }

  const href = $("table#games_table tbody tr td.title a").first().attr("href");
  if (!href) return null;

  return href.startsWith("http") ? href : `${BASE}${href}`;
}

/**
 * Read the six price cells off a product page.
 *
 * The element ids are stable and have been for years; the surrounding markup is
 * not, so we anchor on the id and take whatever text the cell holds.
 */
function parseProductPage(html: string): PricePoint[] {
  const $ = cheerio.load(html);

  const cells: Array<[string, string]> = [
    [GRADE_LABELS.loose, "#used_price"],
    [GRADE_LABELS.cib, "#complete_price"],
    [GRADE_LABELS.new, "#new_price"],
    [GRADE_LABELS.graded, "#graded_price"],
    [GRADE_LABELS.boxOnly, "#box_only_price"],
    [GRADE_LABELS.manualOnly, "#manual_only_price"],
  ];

  const prices: PricePoint[] = [];

  for (const [label, selector] of cells) {
    const text = $(`${selector} .price`).first().text() || $(selector).first().text();
    const parsed = parseMoney(text);
    if (parsed && parsed.amount > 0) {
      prices.push({ label, amount: parsed.amount, currency: parsed.currency });
    }
  }

  return prices;
}

async function viaScrape(card: Card): Promise<ProviderResult> {
  const { value, cached: fromCache } = await cached(`pc:scrape:${card.id}`, async () => {
    const productUrl = await findProductUrl(card);
    if (!productUrl) return { prices: [] as PricePoint[], productUrl: null };

    const html = await fetchHtml(productUrl);
    return { prices: parseProductPage(html), productUrl };
  });

  if (!value.productUrl) {
    return makeResult("pricecharting", "PriceCharting", "empty", "scrape", {
      message: `No PriceCharting product found for "${searchTerm(card)}".`,
      cached: fromCache,
    });
  }

  return makeResult(
    "pricecharting",
    "PriceCharting",
    value.prices.length > 0 ? "ok" : "empty",
    "scrape",
    {
      prices: value.prices,
      sourceUrl: value.productUrl,
      cached: fromCache,
      message:
        value.prices.length === 0
          ? "Found the product page but could not read any prices from it — PriceCharting may have changed its markup."
          : undefined,
    },
  );
}

export const priceChartingProvider: PriceProvider = {
  id: "pricecharting",
  label: "PriceCharting",

  async fetch(card: Card): Promise<ProviderResult[]> {
    if (env.priceChartingToken) {
      try {
        return [await viaApi(card)];
      } catch (error) {
        // A dead token should not cost us the source entirely.
        if (!env.scrapeEnabled) return [errored("pricecharting", "PriceCharting", "api", error)];
      }
    }

    if (!env.scrapeEnabled) {
      return [
        disabled(
          "pricecharting",
          "PriceCharting",
          "Set PRICECHARTING_TOKEN, or set SCRAPE_ENABLED=true to read the public product page.",
        ),
      ];
    }

    try {
      return [await viaScrape(card)];
    } catch (error) {
      return [errored("pricecharting", "PriceCharting", "scrape", error)];
    }
  },
};

export const __testing = { parseProductPage, searchTerm, centsToDollars };
