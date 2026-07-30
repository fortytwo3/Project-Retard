import * as cheerio from "cheerio";
import type { AnyNode } from "domhandler";

import { detectGrade, parseMoney } from "../money";
import type { SoldListing } from "../types";

/**
 * Parser for eBay's sold-listings search results.
 *
 * eBay has run at least three different result-card markups in recent years and
 * A/B tests between them, so every field is read through a list of candidate
 * selectors rather than one. If all of them miss we drop the row instead of
 * emitting a listing with a zero price.
 */

const ITEM_SELECTORS = [
  "li.s-item",
  "li.s-card",
  ".srp-results .s-item",
  ".su-card-container",
].join(", ");

const TITLE_SELECTORS = [
  ".s-item__title",
  ".s-card__title",
  ".su-card-container__header .su-styled-text",
  "[role='heading']",
];

const PRICE_SELECTORS = [".s-item__price", ".s-card__price", ".su-styled-text.primary"];

const DATE_SELECTORS = [
  ".s-item__title--tagblock .POSITIVE",
  ".s-item__caption--signal",
  ".s-card__caption",
  ".su-card-container__caption",
];

const SHIPPING_SELECTORS = [".s-item__shipping", ".s-item__logisticsCost", ".s-card__logisticsCost"];

/** eBay pads results with a promo card whose title is literally this. */
const PLACEHOLDER_TITLES = new Set(["shop on ebay", "new listing"]);

function firstText($: cheerio.CheerioAPI, node: AnyNode, selectors: string[]): string {
  for (const selector of selectors) {
    const text = $(node).find(selector).first().text().trim();
    if (text) return text;
  }
  return "";
}

/**
 * eBay writes sold dates as "Sold  3 Mar 2025" or "Sold Mar 3, 2025" depending
 * on locale. `Date.parse` handles both once the label is stripped.
 */
export function parseSoldDate(text: string): string | null {
  if (!text) return null;

  const match = text.match(/sold\s+(.*)/i);
  const datePart = (match ? match[1] : text).trim();
  if (!datePart) return null;

  const parsed = Date.parse(datePart);
  if (!Number.isFinite(parsed)) return null;

  // A "date" more than a day in the future means we parsed something that was
  // not a date at all.
  if (parsed > Date.now() + 86_400_000) return null;

  return new Date(parsed).toISOString();
}

/**
 * Price cells sometimes hold a range ("$10.00 to $25.00"). Take the low end,
 * which is the conservative read for a sold price.
 */
function parsePriceCell(text: string): { amount: number; currency: "USD" | "EUR" | "GBP" } | null {
  const [first] = text.split(/\s+to\s+/i);
  const parsed = parseMoney(first ?? text);
  return parsed && parsed.amount > 0 ? parsed : null;
}

export function parseSoldListings(html: string, limit = 60): SoldListing[] {
  const $ = cheerio.load(html);
  const listings: SoldListing[] = [];

  $(ITEM_SELECTORS).each((_, node) => {
    if (listings.length >= limit) return false;

    const title = firstText($, node, TITLE_SELECTORS).replace(/^new listing\s*/i, "").trim();
    if (!title || PLACEHOLDER_TITLES.has(title.toLowerCase())) return;

    const price = parsePriceCell(firstText($, node, PRICE_SELECTORS));
    if (!price) return;

    const shippingText = firstText($, node, SHIPPING_SELECTORS);
    const href = $(node).find("a[href*='/itm/']").first().attr("href") ?? null;
    const image =
      $(node).find("img").first().attr("src") ??
      $(node).find("img").first().attr("data-src") ??
      null;

    listings.push({
      title,
      price: price.amount,
      currency: price.currency,
      soldAt: parseSoldDate(firstText($, node, DATE_SELECTORS)),
      // Strip eBay's tracking query string so the links stay readable.
      url: href ? href.split("?")[0] : null,
      imageUrl: image,
      shippingIncluded: /free/i.test(shippingText),
      detectedGrade: detectGrade(title),
    });

    return;
  });

  return listings;
}

/** Build the sold-and-completed search URL for a query. */
export function soldSearchUrl(query: string, categoryId?: string): string {
  const params = new URLSearchParams({
    _nkw: query,
    LH_Sold: "1",
    LH_Complete: "1",
    _ipg: "60",
    // Most recently ended first, so the sample reflects the current market.
    _sop: "13",
  });
  if (categoryId) params.set("_sacat", categoryId);

  return `https://www.ebay.com/sch/i.html?${params.toString()}`;
}
