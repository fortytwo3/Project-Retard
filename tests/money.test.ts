import { describe, expect, it } from "vitest";

import { detectGrade, formatMoney, parseMoney } from "@/lib/money";

describe("parseMoney", () => {
  it("reads an eBay US price cell", () => {
    expect(parseMoney("US $124.99")).toEqual({ amount: 124.99, currency: "USD" });
  });

  it("strips thousands separators", () => {
    expect(parseMoney("$1,250.00")).toEqual({ amount: 1250, currency: "USD" });
  });

  it("reads a pound price", () => {
    expect(parseMoney("£89.50")).toEqual({ amount: 89.5, currency: "GBP" });
  });

  it("treats a trailing comma-two-digits as a decimal separator", () => {
    expect(parseMoney("43,50 €")).toEqual({ amount: 43.5, currency: "EUR" });
  });

  it("handles European grouping and decimals together", () => {
    expect(parseMoney("€1.250,00")).toEqual({ amount: 1250, currency: "EUR" });
  });

  it("recognises a bare currency code", () => {
    expect(parseMoney("EUR 43.50")).toEqual({ amount: 43.5, currency: "EUR" });
  });

  it("returns null when there is no number", () => {
    expect(parseMoney("Best offer accepted")).toBeNull();
  });

  it("returns null for empty input", () => {
    expect(parseMoney("")).toBeNull();
  });
});

describe("formatMoney", () => {
  it("formats a USD amount", () => {
    expect(formatMoney(124.5, "USD")).toBe("$124.50");
  });

  it("renders a dash for a missing amount", () => {
    expect(formatMoney(null)).toBe("—");
  });
});

describe("detectGrade", () => {
  it("finds a PSA grade", () => {
    expect(detectGrade("Charizard ex 054/165 PSA 10 GEM MINT")).toBe("PSA 10");
  });

  it("normalises spacing and casing", () => {
    expect(detectGrade("charizard psa10 mint")).toBe("PSA 10");
  });

  it("reads half grades", () => {
    expect(detectGrade("Pikachu BGS 9.5")).toBe("BGS 9.5");
  });

  it("recognises an explicitly ungraded listing", () => {
    expect(detectGrade("Charizard 4/102 raw near mint")).toBe("Ungraded");
  });

  it("returns null when no grade is mentioned", () => {
    expect(detectGrade("Charizard 4/102 Base Set")).toBeNull();
  });
});
