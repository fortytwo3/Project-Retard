import { describe, expect, it } from "vitest";

import { median, removeOutliers, summariseSales, trendPct, trimmedMean } from "@/lib/stats";
import type { SoldListing } from "@/lib/types";

function sale(price: number, soldAt: string | null = null, title = "Charizard ex 054/165"): SoldListing {
  return {
    title,
    price,
    currency: "USD",
    soldAt,
    url: null,
    imageUrl: null,
    shippingIncluded: false,
    detectedGrade: null,
  };
}

describe("median", () => {
  it("averages the middle pair for an even count", () => {
    expect(median([1, 2, 3, 4])).toBe(2.5);
  });

  it("takes the middle value for an odd count", () => {
    expect(median([5, 1, 3])).toBe(3);
  });

  it("returns 0 for an empty sample", () => {
    expect(median([])).toBe(0);
  });
});

describe("removeOutliers", () => {
  it("drops a graded copy from a sample of raw sales", () => {
    const prices = [20, 21, 22, 19, 23, 20, 4000];
    expect(removeOutliers(prices)).not.toContain(4000);
  });

  it("leaves small samples untouched, since there is no distribution yet", () => {
    const prices = [10, 5000, 12];
    expect(removeOutliers(prices)).toHaveLength(3);
  });

  it("keeps everything when all values are identical", () => {
    expect(removeOutliers([7, 7, 7, 7, 7])).toEqual([7, 7, 7, 7, 7]);
  });
});

describe("trimmedMean", () => {
  it("is not dragged upward by a single extreme sale", () => {
    const withOutlier = trimmedMean([20, 21, 22, 19, 23, 20, 4000]);
    expect(withOutlier).toBeGreaterThan(19);
    expect(withOutlier).toBeLessThan(24);
  });
});

describe("trendPct", () => {
  it("reports a rise when newer sales are higher", () => {
    const listings = [
      sale(10, "2025-01-01"),
      sale(10, "2025-01-02"),
      sale(10, "2025-01-03"),
      sale(20, "2025-02-01"),
      sale(20, "2025-02-02"),
      sale(20, "2025-02-03"),
    ];
    expect(trendPct(listings)).toBeCloseTo(100, 0);
  });

  it("returns null without enough dated sales to be meaningful", () => {
    expect(trendPct([sale(10, "2025-01-01"), sale(12, "2025-01-02")])).toBeNull();
  });

  it("returns null when no sale carries a date", () => {
    expect(trendPct(Array.from({ length: 10 }, () => sale(10)))).toBeNull();
  });
});

describe("summariseSales", () => {
  it("summarises a sample", () => {
    const summary = summariseSales([sale(10), sale(20), sale(30)]);

    expect(summary).toMatchObject({ count: 3, min: 10, max: 30, median: 20, currency: "USD" });
  });

  it("returns undefined for an empty sample", () => {
    expect(summariseSales([])).toBeUndefined();
  });
});
