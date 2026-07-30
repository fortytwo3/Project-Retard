import { describe, expect, it } from "vitest";

import { __testing as consensusTesting } from "@/lib/providers";
import { __testing as pcTesting } from "@/lib/providers/pricecharting";
import { marketplaceQuery } from "@/lib/providers/types";
import type { Card, ProviderResult, SoldSummary } from "@/lib/types";

const card = {
  id: "sv3pt5-54",
  name: "Charizard ex",
  number: "054",
  printedTotal: 165,
  setName: "151",
} as Card;

function result(overrides: Partial<ProviderResult> & Pick<ProviderResult, "providerId">) {
  return {
    label: overrides.providerId,
    status: "ok",
    method: "api",
    sourceUrl: null,
    prices: [],
    fetchedAt: "2025-03-01T00:00:00.000Z",
    cached: false,
    ...overrides,
  } as ProviderResult;
}

function soldSummary(count: number, mean: number): SoldSummary {
  return {
    count,
    currency: "USD",
    min: mean * 0.8,
    max: mean * 1.2,
    median: mean,
    trimmedMean: mean,
    trendPct: null,
    windowStart: null,
    windowEnd: null,
  };
}

describe("computeConsensus", () => {
  it("prefers a healthy sample of completed eBay sales", () => {
    const consensus = consensusTesting.computeConsensus([
      result({
        providerId: "ebay",
        label: "eBay — Recently Sold",
        soldSummary: soldSummary(20, 400),
      }),
      result({
        providerId: "tcgplayer",
        prices: [{ label: "Holofoil — Market", amount: 355, currency: "USD" }],
      }),
    ]);

    expect(consensus.amount).toBe(400);
    expect(consensus.basis).toContain("20 recent eBay sales");
  });

  it("blends a thin eBay sample with the TCGplayer market price", () => {
    const consensus = consensusTesting.computeConsensus([
      result({
        providerId: "ebay",
        label: "eBay — Recently Sold",
        soldSummary: soldSummary(2, 400),
      }),
      result({
        providerId: "tcgplayer",
        prices: [{ label: "Holofoil — Market", amount: 300, currency: "USD" }],
      }),
    ]);

    expect(consensus.amount).toBe(350);
    expect(consensus.basis).toContain("Blend");
  });

  it("does not treat active asking prices as sales", () => {
    const consensus = consensusTesting.computeConsensus([
      result({
        providerId: "ebay",
        label: "eBay — Active Listings",
        soldSummary: soldSummary(30, 900),
      }),
      result({
        providerId: "tcgplayer",
        prices: [{ label: "Holofoil — Market", amount: 355, currency: "USD" }],
      }),
    ]);

    expect(consensus.amount).toBe(355);
    expect(consensus.basis).toBe("TCGplayer market price");
  });

  it("falls back to PriceCharting ungraded", () => {
    const consensus = consensusTesting.computeConsensus([
      result({
        providerId: "pricecharting",
        prices: [{ label: "Ungraded", amount: 88, currency: "USD" }],
      }),
    ]);

    expect(consensus).toMatchObject({ amount: 88, basis: "PriceCharting ungraded" });
  });

  it("reports honestly when nothing returned a price", () => {
    const consensus = consensusTesting.computeConsensus([
      result({ providerId: "tcgplayer", status: "empty" }),
    ]);

    expect(consensus.amount).toBeNull();
  });

  it("ignores prices from a provider that errored", () => {
    const consensus = consensusTesting.computeConsensus([
      result({
        providerId: "tcgplayer",
        status: "error",
        prices: [{ label: "Holofoil — Market", amount: 355, currency: "USD" }],
      }),
    ]);

    expect(consensus.amount).toBeNull();
  });
});

describe("sortResults", () => {
  it("puts sources with data ahead of sources without", () => {
    const sorted = consensusTesting.sortResults([
      result({ providerId: "cardmarket", status: "empty" }),
      result({ providerId: "tcgplayer", status: "ok" }),
    ]);

    expect(sorted.map((r) => r.providerId)).toEqual(["tcgplayer", "cardmarket"]);
  });

  it("leads with eBay when several sources have data", () => {
    const sorted = consensusTesting.sortResults([
      result({ providerId: "cardmarket" }),
      result({ providerId: "tcgplayer" }),
      result({ providerId: "ebay" }),
    ]);

    expect(sorted[0].providerId).toBe("ebay");
  });
});

describe("marketplaceQuery", () => {
  it("includes the collector number, which is what disambiguates a listing", () => {
    expect(marketplaceQuery(card)).toBe("Charizard ex 151 054/165");
  });
});

describe("pricecharting", () => {
  it("converts API cents to dollars", () => {
    expect(pcTesting.centsToDollars(38999)).toBe(389.99);
  });

  it("treats a zero price as absent", () => {
    expect(pcTesting.centsToDollars(0)).toBeNull();
  });

  it("maps the six price cells to card grades", () => {
    const html = `
      <div id="used_price"><span class="price">$88.00</span></div>
      <div id="complete_price"><span class="price">$140.00</span></div>
      <div id="new_price"><span class="price">$190.00</span></div>
      <div id="graded_price"><span class="price">$260.00</span></div>
      <div id="box_only_price"><span class="price">$320.00</span></div>
      <div id="manual_only_price"><span class="price">$410.00</span></div>`;

    expect(pcTesting.parseProductPage(html)).toEqual([
      { label: "Ungraded", amount: 88, currency: "USD" },
      { label: "Grade 7", amount: 140, currency: "USD" },
      { label: "Grade 8", amount: 190, currency: "USD" },
      { label: "Grade 9", amount: 260, currency: "USD" },
      { label: "Grade 9.5", amount: 320, currency: "USD" },
      { label: "PSA 10", amount: 410, currency: "USD" },
    ]);
  });

  it("skips cells PriceCharting leaves blank", () => {
    const html = `
      <div id="used_price"><span class="price">$88.00</span></div>
      <div id="manual_only_price"><span class="price">-</span></div>`;

    expect(pcTesting.parseProductPage(html)).toEqual([
      { label: "Ungraded", amount: 88, currency: "USD" },
    ]);
  });
});
