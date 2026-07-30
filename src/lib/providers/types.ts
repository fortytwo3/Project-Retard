import type { Card, ProviderResult, ProviderStatus, SourceMethod } from "../types";

/**
 * A price source. Each returns one or more `ProviderResult`s because some
 * upstreams cover several marketplaces at once — pokemontcg.io, for instance,
 * carries both TCGplayer and Cardmarket data in a single response.
 */
export interface PriceProvider {
  id: string;
  label: string;
  fetch(card: Card): Promise<ProviderResult[]>;
}

export function makeResult(
  providerId: string,
  label: string,
  status: ProviderStatus,
  method: SourceMethod,
  overrides: Partial<ProviderResult> = {},
): ProviderResult {
  return {
    providerId,
    label,
    status,
    method,
    sourceUrl: null,
    prices: [],
    fetchedAt: new Date().toISOString(),
    cached: false,
    ...overrides,
  };
}

/** Convenience for the "this source is switched off" path. */
export function disabled(providerId: string, label: string, message: string): ProviderResult {
  return makeResult(providerId, label, "disabled", "none", { message });
}

export function errored(
  providerId: string,
  label: string,
  method: SourceMethod,
  error: unknown,
  sourceUrl: string | null = null,
): ProviderResult {
  return makeResult(providerId, label, "error", method, {
    message: error instanceof Error ? error.message : String(error),
    sourceUrl,
  });
}

/**
 * The search phrase used against marketplaces that index by title rather than
 * by card id. Includes the collector number because "Charizard" alone returns
 * thousands of unrelated cards, and the number is what makes a listing
 * unambiguous.
 */
export function marketplaceQuery(card: Card): string {
  const parts = [card.name, card.setName];
  if (card.number) {
    parts.push(card.printedTotal ? `${card.number}/${card.printedTotal}` : card.number);
  }
  return parts.join(" ");
}
