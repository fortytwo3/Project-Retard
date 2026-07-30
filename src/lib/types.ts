/** Shared domain types. Everything the UI renders is normalised into these. */

export type Currency = "USD" | "EUR" | "GBP";

/** A card as identified by pokemontcg.io. */
export interface Card {
  id: string;
  name: string;
  /** Collector number as printed, e.g. "058" or "TG12". */
  number: string;
  /** Total printed on the card, e.g. "197" from "058/197". */
  printedTotal: number | null;
  setId: string;
  setName: string;
  setSeries: string;
  setReleaseDate: string | null;
  rarity: string | null;
  /** Small/large art from pokemontcg.io. */
  images: { small: string; large: string };
  /** Deep links kept for the "open on…" buttons. */
  tcgplayerUrl: string | null;
  cardmarketUrl: string | null;
  /** Raw price blobs from pokemontcg.io, passed through to the provider. */
  rawTcgplayer: unknown;
  rawCardmarket: unknown;
}

/** A candidate match produced by the identifier, ranked by `score`. */
export interface CardCandidate {
  card: Card;
  /** 0..1, higher is better. */
  score: number;
  /** Human-readable reasons the matcher liked this card. */
  reasons: string[];
}

/**
 * One price point. `variant` distinguishes printings (Holofoil vs Reverse
 * Holofoil) and grades (PSA 10) — whatever the source reports.
 */
export interface PricePoint {
  label: string;
  /** Null when a source lists the variant but has no price for it. */
  amount: number | null;
  currency: Currency;
  /** Optional context, e.g. "based on 14 sales". */
  note?: string;
}

/** A single completed eBay sale. */
export interface SoldListing {
  title: string;
  price: number;
  currency: Currency;
  /** ISO date of the sale, when the source exposes one. */
  soldAt: string | null;
  url: string | null;
  imageUrl: string | null;
  /** True when shipping is bundled into `price`. */
  shippingIncluded: boolean;
  /** Parsed out of the title when present, e.g. "PSA 10". */
  detectedGrade: string | null;
}

/** Summary statistics over a set of sold listings. */
export interface SoldSummary {
  count: number;
  currency: Currency;
  min: number;
  max: number;
  median: number;
  /** Mean after discarding statistical outliers — the headline number. */
  trimmedMean: number;
  /** Percent change between the oldest and newest halves of the window. */
  trendPct: number | null;
  /** ISO dates bounding the sample. */
  windowStart: string | null;
  windowEnd: string | null;
}

export type ProviderStatus = "ok" | "empty" | "error" | "disabled";

/** How a provider got its numbers, surfaced in the UI so you can judge them. */
export type SourceMethod = "api" | "scrape" | "none";

export interface ProviderResult {
  providerId: string;
  label: string;
  status: ProviderStatus;
  method: SourceMethod;
  /** Present when status is "error" or "disabled". */
  message?: string;
  /** Page a human can open to verify. */
  sourceUrl: string | null;
  prices: PricePoint[];
  /** Only eBay populates these. */
  soldListings?: SoldListing[];
  soldSummary?: SoldSummary;
  /** ISO timestamp of when the data was fetched. */
  fetchedAt: string;
  /** True when served from the local cache rather than the network. */
  cached: boolean;
}

export interface PriceReport {
  card: Card;
  results: ProviderResult[];
  /**
   * Cross-source headline: the value we think the card is actually worth,
   * preferring real completed sales over asking prices.
   */
  consensus: {
    amount: number | null;
    currency: Currency;
    basis: string;
  };
  generatedAt: string;
}

/** What the OCR pass extracts from a captured frame. */
export interface ScanParse {
  /** Best guess at the card name. */
  name: string | null;
  /** Collector number, e.g. "058". */
  number: string | null;
  /** Set total, e.g. 197 from "058/197". */
  printedTotal: number | null;
  /** Set abbreviation if one was legible, e.g. "SVI". */
  setCode: string | null;
  /** Everything OCR saw, for debugging and manual fallback. */
  rawText: string;
  /** Mean OCR confidence, 0..100. */
  confidence: number;
}

/** A card the user saved, with the price at the time they scanned it. */
export interface HistoryEntry {
  id: string;
  cardId: string;
  cardName: string;
  setName: string;
  imageUrl: string;
  scannedAt: string;
  valueAtScan: number | null;
  currency: Currency;
}
