import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { identifyCard } from "@/lib/pokemontcg";
import { buildPriceReport } from "@/lib/providers";
import { parseScan } from "@/lib/ocr/parse";
import type { Card } from "@/lib/types";

/**
 * End-to-end exercise of the server pipeline — OCR text in, price report out —
 * with every outbound request served from a fixture.
 *
 * These cover the wiring the unit tests cannot: that the identifier's queries
 * reach the right endpoint, that each provider parses its own upstream shape,
 * that one dead source does not sink the report, and that the consensus picks
 * the number we intend it to.
 */

const POKEMONTCG_CARD = {
  id: "sv3pt5-54",
  name: "Charizard ex",
  number: "54",
  rarity: "Double Rare",
  set: {
    id: "sv3pt5",
    name: "151",
    series: "Scarlet & Violet",
    printedTotal: 165,
    releaseDate: "2023/09/22",
  },
  images: {
    small: "https://images.pokemontcg.io/sv3pt5/54.png",
    large: "https://images.pokemontcg.io/sv3pt5/54_hires.png",
  },
  tcgplayer: {
    url: "https://prices.pokemontcg.io/tcgplayer/sv3pt5-54",
    prices: {
      holofoil: { low: 320.5, mid: 360, high: 500, market: 355.25, directLow: 349.99 },
    },
  },
  cardmarket: {
    url: "https://prices.pokemontcg.io/cardmarket/sv3pt5-54",
    prices: { averageSellPrice: 310.4, lowPrice: 289, trendPrice: 318.5, avg30: 305.1 },
  },
};

const PRICECHARTING_SEARCH = `
<html><body>
  <table id="games_table"><tbody>
    <tr><td class="title"><a href="/game/pokemon-151/charizard-ex-54">Charizard ex #54</a></td></tr>
  </tbody></table>
</body></html>`;

const PRICECHARTING_PRODUCT = `
<html><body>
  <div id="used_price"><span class="price">$88.00</span></div>
  <div id="complete_price"><span class="price">$140.00</span></div>
  <div id="graded_price"><span class="price">$260.00</span></div>
  <div id="manual_only_price"><span class="price">$410.00</span></div>
</body></html>`;

/** Eight sold rows plus one bulk lot that the relevance filter must reject. */
const EBAY_SOLD = `
<ul class="srp-results">
  ${[402, 388, 395, 410, 399, 385, 405, 392]
    .map(
      (price, index) => `
  <li class="s-item">
    <a class="s-item__link" href="https://www.ebay.com/itm/${index}"></a>
    <div class="s-item__title">Charizard ex 054/165 Pokemon 151 NM</div>
    <span class="s-item__price">$${price}.00</span>
    <div class="s-item__title--tagblock"><span class="POSITIVE">Sold  Mar ${index + 1}, 2025</span></div>
  </li>`,
    )
    .join("")}
  <li class="s-item">
    <a class="s-item__link" href="https://www.ebay.com/itm/lot"></a>
    <div class="s-item__title">Charizard ex lot of 12 Pokemon cards bundle</div>
    <span class="s-item__price">$1200.00</span>
  </li>
</ul>`;

function textResponse(body: string, contentType: string) {
  return new Response(body, { status: 200, headers: { "Content-Type": contentType } });
}

/** Routes stubbed requests by hostname and path. */
function stubFetch(options: { ebay?: string | Error; priceCharting?: boolean } = {}) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input.toString();

    if (url.includes("api.pokemontcg.io/v2/cards/")) {
      return textResponse(JSON.stringify({ data: POKEMONTCG_CARD }), "application/json");
    }
    if (url.includes("api.pokemontcg.io/v2/cards")) {
      return textResponse(JSON.stringify({ data: [POKEMONTCG_CARD] }), "application/json");
    }
    if (url.includes("pricecharting.com/search-products")) {
      if (options.priceCharting === false) throw new Error("PriceCharting unreachable");
      return textResponse(PRICECHARTING_SEARCH, "text/html");
    }
    if (url.includes("pricecharting.com/game/")) {
      return textResponse(PRICECHARTING_PRODUCT, "text/html");
    }
    if (url.includes("ebay.com/sch/")) {
      if (options.ebay instanceof Error) throw options.ebay;
      return textResponse(options.ebay ?? EBAY_SOLD, "text/html");
    }

    throw new Error(`Unstubbed request: ${url}`);
  });
}

const card: Card = {
  id: "sv3pt5-54",
  name: "Charizard ex",
  number: "54",
  printedTotal: 165,
  setId: "sv3pt5",
  setName: "151",
  setSeries: "Scarlet & Violet",
  setReleaseDate: "2023/09/22",
  rarity: "Double Rare",
  images: { small: "", large: "" },
  tcgplayerUrl: null,
  cardmarketUrl: null,
  rawTcgplayer: null,
  rawCardmarket: null,
};

const originalFetch = globalThis.fetch;

beforeEach(() => {
  vi.stubEnv("SCRAPE_ENABLED", "true");
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.unstubAllEnvs();
});

describe("identify → card", () => {
  it("turns raw OCR text into the right card", async () => {
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    const scan = parseScan("Charizard ex\n330 HP\nIllus. 5ban Graphics\n054/165  MEW", 81);
    const candidates = await identifyCard(scan);

    expect(candidates.length).toBeGreaterThan(0);
    expect(candidates[0].card.id).toBe("sv3pt5-54");
    expect(candidates[0].score).toBeGreaterThan(0.9);
  });

  it("still finds the card when OCR mangles the name but gets the number", async () => {
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    const candidates = await identifyCard(parseScan("Chariz@rd ex\n054/165", 40));

    expect(candidates[0]?.card.id).toBe("sv3pt5-54");
  });
});

describe("buildPriceReport", () => {
  it("collects every source and leads with completed eBay sales", async () => {
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    const report = await buildPriceReport(card);
    const ids = report.results.map((r) => r.providerId);

    expect(ids).toContain("ebay");
    expect(ids).toContain("tcgplayer");
    expect(ids).toContain("cardmarket");
    expect(ids).toContain("pricecharting");

    // eBay sold data is the most trustworthy signal, so it sorts first.
    expect(report.results[0].providerId).toBe("ebay");
  });

  it("reads TCGplayer market and low from the pokemontcg payload", async () => {
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    const report = await buildPriceReport(card);
    const tcg = report.results.find((r) => r.providerId === "tcgplayer");

    expect(tcg?.status).toBe("ok");
    expect(tcg?.prices).toContainEqual({
      label: "Holofoil — Market",
      amount: 355.25,
      currency: "USD",
    });
  });

  it("maps PriceCharting's columns onto card grades", async () => {
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    const report = await buildPriceReport(card);
    const pc = report.results.find((r) => r.providerId === "pricecharting");

    expect(pc?.method).toBe("scrape");
    expect(pc?.prices.find((p) => p.label === "PSA 10")?.amount).toBe(410);
    expect(pc?.prices.find((p) => p.label === "Ungraded")?.amount).toBe(88);
  });

  it("drops the bulk lot before averaging sold prices", async () => {
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    const report = await buildPriceReport(card);
    const ebay = report.results.find((r) => r.providerId === "ebay");

    expect(ebay?.soldSummary?.count).toBe(8);
    expect(ebay?.soldSummary?.max).toBeLessThan(500);
    expect(ebay?.soldListings?.some((l) => l.title.includes("lot of 12"))).toBe(false);
  });

  it("bases the headline price on the eBay sales", async () => {
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    const report = await buildPriceReport(card);

    expect(report.consensus.amount).toBeCloseTo(397, 0);
    expect(report.consensus.basis).toContain("eBay");
  });

  it("survives a source going down and still prices the card", async () => {
    globalThis.fetch = stubFetch({ priceCharting: false }) as unknown as typeof fetch;

    const report = await buildPriceReport(card);
    const pc = report.results.find((r) => r.providerId === "pricecharting");

    expect(pc?.status).toBe("error");
    expect(report.consensus.amount).toBeCloseTo(397, 0);
  });

  it("falls back to TCGplayer when eBay serves a bot-check page", async () => {
    globalThis.fetch = stubFetch({
      ebay: "<html><body>Pardon our interruption</body></html>",
    }) as unknown as typeof fetch;

    const report = await buildPriceReport(card);
    const ebay = report.results.find((r) => r.providerId === "ebay");

    expect(ebay?.status).toBe("empty");
    expect(report.consensus).toMatchObject({
      amount: 355.25,
      basis: "TCGplayer market price",
    });
  });

  it("reports honestly when every source is disabled", async () => {
    vi.stubEnv("SCRAPE_ENABLED", "false");
    globalThis.fetch = stubFetch() as unknown as typeof fetch;

    // Re-import so the providers observe the stubbed environment.
    vi.resetModules();
    const { buildPriceReport: build } = await import("@/lib/providers");

    const report = await build({ ...card, rawTcgplayer: null, rawCardmarket: null });
    const ebay = report.results.find((r) => r.providerId === "ebay");

    expect(ebay?.status).toBe("disabled");
  });
});
