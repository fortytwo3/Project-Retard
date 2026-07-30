import { describe, expect, it } from "vitest";

import { __testing } from "@/lib/pokemontcg";
import type { Card, ScanParse } from "@/lib/types";

const { scoreCard, normaliseNumber, similarity } = __testing;

function card(overrides: Partial<Card> = {}): Card {
  return {
    id: "sv3pt5-54",
    name: "Charizard ex",
    number: "054",
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
    ...overrides,
  };
}

function scan(overrides: Partial<ScanParse> = {}): ScanParse {
  return {
    name: null,
    number: null,
    printedTotal: null,
    setCode: null,
    rawText: "",
    confidence: 0,
    ...overrides,
  };
}

describe("normaliseNumber", () => {
  it("ignores leading zeros", () => {
    expect(normaliseNumber("054")).toBe(normaliseNumber("54"));
  });

  it("keeps a subset prefix", () => {
    expect(normaliseNumber("TG012")).toBe("TG12");
  });
});

describe("similarity", () => {
  it("scores identical strings as 1", () => {
    expect(similarity("charizard", "charizard")).toBe(1);
  });

  it("stays high through a single OCR error", () => {
    expect(similarity("charizard", "chariza rd".replace(" ", ""))).toBeGreaterThan(0.9);
  });

  it("scores unrelated names low", () => {
    expect(similarity("charizard", "blastoise")).toBeLessThan(0.4);
  });
});

describe("scoreCard", () => {
  it("scores a full match near 1", () => {
    const { score } = scoreCard(
      card(),
      scan({ name: "Charizard ex", number: "054", printedTotal: 165 }),
    );
    expect(score).toBeGreaterThan(0.95);
  });

  it("still clears the candidate threshold on the number alone", () => {
    const { score } = scoreCard(card(), scan({ number: "054", printedTotal: 165 }));
    expect(score).toBeGreaterThan(0.35);
  });

  it("scores a wrong card below the threshold", () => {
    const { score } = scoreCard(
      card({ name: "Blastoise ex", number: "009" }),
      scan({ name: "Charizard ex", number: "054" }),
    );
    expect(score).toBeLessThan(0.35);
  });

  it("explains why it matched", () => {
    const { reasons } = scoreCard(
      card(),
      scan({ name: "Charizard ex", number: "054", printedTotal: 165 }),
    );
    expect(reasons).toContain("Exact name match");
    expect(reasons).toContain("Card number 054");
  });
});
