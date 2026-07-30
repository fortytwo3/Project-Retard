import { describe, expect, it } from "vitest";

import { parseCardName, parseCollectorNumber, parseScan, parseSetCode } from "@/lib/ocr/parse";

/**
 * The OCR strings below are shaped like real Tesseract output from card photos:
 * inconsistent casing, letters substituted for digits, and the surrounding card
 * furniture (HP, stage, illustrator credit) mixed in with the fields we want.
 */

describe("parseCollectorNumber", () => {
  it("reads a plain collector number", () => {
    expect(parseCollectorNumber("058/197")).toEqual({ number: "058", printedTotal: 197 });
  });

  it("tolerates spaces around the slash", () => {
    expect(parseCollectorNumber("Illus. Mitsuhiro Arita  4 / 102")).toEqual({
      number: "4",
      printedTotal: 102,
    });
  });

  it("repairs letters OCR substituted for digits", () => {
    // "O5B" is a misread of "058".
    expect(parseCollectorNumber("O5B/197")).toEqual({ number: "058", printedTotal: 197 });
  });

  it("keeps meaningful letter prefixes on gallery subsets", () => {
    expect(parseCollectorNumber("TG12/TG30")).toEqual({ number: "TG12", printedTotal: 30 });
  });

  it("reads slash-less promo numbering", () => {
    expect(parseCollectorNumber("SWSH284")).toEqual({ number: "SWSH284", printedTotal: null });
  });

  it("falls back to a bare number on its own line", () => {
    expect(parseCollectorNumber("Charizard\n\n144\n")).toEqual({
      number: "144",
      printedTotal: null,
    });
  });

  it("returns nulls when there is nothing number-shaped", () => {
    expect(parseCollectorNumber("Weakness Resistance Retreat")).toEqual({
      number: null,
      printedTotal: null,
    });
  });
});

describe("parseCardName", () => {
  it("picks the name over surrounding card furniture", () => {
    const ocr = ["Stage 2", "Charizard 170 HP", "Evolves from Charmeleon", "Fire Spin 200"].join(
      "\n",
    );
    expect(parseCardName(ocr)).toBe("Charizard");
  });

  it("keeps the ex suffix and normalises its casing", () => {
    expect(parseCardName("CHARIZARD EX\n330 HP")).toBe("Charizard ex");
  });

  it("keeps VMAX in caps", () => {
    expect(parseCardName("Pikachu VMAX\n310 HP")).toBe("Pikachu VMAX");
  });

  it("strips a trailing HP value from the name line", () => {
    expect(parseCardName("Mewtwo 130 HP")).toBe("Mewtwo");
  });

  it("handles multi-word names", () => {
    expect(parseCardName("Basic\nIron Valiant\n220 HP")).toBe("Iron Valiant");
  });

  it("returns null when every line is noise", () => {
    expect(parseCardName("Weakness\nResistance\nRetreat Cost")).toBeNull();
  });
});

describe("parseSetCode", () => {
  it("finds a modern three-letter set code", () => {
    expect(parseSetCode("058/197 OBF")).toBe("OBF");
  });

  it("returns null when no known code is present", () => {
    expect(parseSetCode("058/197")).toBeNull();
  });
});

describe("parseScan", () => {
  it("combines every field from a realistic two-region read", () => {
    const ocr = ["Charizard ex", "330 HP", "", "Illus. 5ban Graphics", "054/165  MEW"].join("\n");

    const result = parseScan(ocr, 82.4);

    expect(result).toMatchObject({
      name: "Charizard ex",
      number: "054",
      printedTotal: 165,
      setCode: "MEW",
      confidence: 82.4,
    });
  });
});
