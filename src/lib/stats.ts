import type { SoldListing, SoldSummary } from "./types";

export function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

function quantile(sorted: number[], q: number): number {
  if (sorted.length === 0) return 0;
  const pos = (sorted.length - 1) * q;
  const base = Math.floor(pos);
  const rest = pos - base;
  const next = sorted[base + 1];
  return next === undefined ? sorted[base] : sorted[base] + rest * (next - sorted[base]);
}

/**
 * Discard values outside the usual 1.5×IQR fences.
 *
 * This matters more for cards than for most datasets: a search for "Charizard
 * 4/102" will surface a $12 damaged copy and a $4000 graded one alongside forty
 * ordinary sales, and a plain mean lands somewhere nobody would ever trade at.
 * Below five samples there is no distribution to speak of, so pass them through.
 */
export function removeOutliers(values: number[]): number[] {
  if (values.length < 5) return [...values];

  const sorted = [...values].sort((a, b) => a - b);
  const q1 = quantile(sorted, 0.25);
  const q3 = quantile(sorted, 0.75);
  const iqr = q3 - q1;

  if (iqr === 0) return sorted;

  const low = q1 - 1.5 * iqr;
  const high = q3 + 1.5 * iqr;
  const kept = sorted.filter((v) => v >= low && v <= high);

  return kept.length > 0 ? kept : sorted;
}

export function trimmedMean(values: number[]): number {
  const kept = removeOutliers(values);
  if (kept.length === 0) return 0;
  return kept.reduce((sum, v) => sum + v, 0) / kept.length;
}

/**
 * Percent change between the older and newer halves of a date-sorted sample.
 * Returns null when there are too few dated sales to say anything honest.
 */
export function trendPct(listings: SoldListing[]): number | null {
  const dated = listings
    .filter((l): l is SoldListing & { soldAt: string } => Boolean(l.soldAt))
    .sort((a, b) => Date.parse(a.soldAt) - Date.parse(b.soldAt));

  if (dated.length < 6) return null;

  const mid = Math.floor(dated.length / 2);
  const older = trimmedMean(dated.slice(0, mid).map((l) => l.price));
  const newer = trimmedMean(dated.slice(mid).map((l) => l.price));

  if (older === 0) return null;
  return ((newer - older) / older) * 100;
}

export function summariseSales(listings: SoldListing[]): SoldSummary | undefined {
  if (listings.length === 0) return undefined;

  const prices = listings.map((l) => l.price).filter((p) => Number.isFinite(p) && p > 0);
  if (prices.length === 0) return undefined;

  const dates = listings
    .map((l) => l.soldAt)
    .filter((d): d is string => Boolean(d))
    .sort();

  return {
    count: prices.length,
    currency: listings[0].currency,
    min: Math.min(...prices),
    max: Math.max(...prices),
    median: median(prices),
    trimmedMean: trimmedMean(prices),
    trendPct: trendPct(listings),
    windowStart: dates[0] ?? null,
    windowEnd: dates[dates.length - 1] ?? null,
  };
}
