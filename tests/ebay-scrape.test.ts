import { describe, expect, it } from "vitest";

import { parseSoldListings, parseSoldDate, soldSearchUrl } from "@/lib/providers/ebay-scrape";
import { __testing } from "@/lib/providers/ebay";
import type { Card } from "@/lib/types";

/** Markup matching eBay's long-running `s-item` result card. */
const LEGACY_HTML = `
<ul class="srp-results">
  <li class="s-item">
    <div class="s-item__title">Shop on eBay</div>
    <span class="s-item__price">$20.00</span>
  </li>
  <li class="s-item">
    <a class="s-item__link" href="https://www.ebay.com/itm/123456?hash=abc"></a>
    <div class="s-item__image-wrapper"><img src="https://i.ebayimg.com/a.jpg" /></div>
    <div class="s-item__title">Charizard ex 054/165 151 PSA 10 Gem Mint</div>
    <span class="s-item__price">$389.99</span>
    <span class="s-item__shipping">Free shipping</span>
    <div class="s-item__title--tagblock"><span class="POSITIVE">Sold  Mar 3, 2025</span></div>
  </li>
  <li class="s-item">
    <a class="s-item__link" href="https://www.ebay.com/itm/789012"></a>
    <div class="s-item__title">Charizard ex 054/165 Pokemon 151 Raw NM</div>
    <span class="s-item__price">$41.00</span>
    <span class="s-item__shipping">+$5.00 shipping</span>
    <div class="s-item__title--tagblock"><span class="POSITIVE">Sold  Feb 28, 2025</span></div>
  </li>
</ul>`;

/** Markup matching the newer `s-card` layout eBay A/B tests against. */
const MODERN_HTML = `
<div class="srp-results">
  <li class="s-card">
    <a href="https://www.ebay.com/itm/555?_trkparms=x"></a>
    <span class="s-card__title">Charizard ex 054/165 SV 151 Special Illustration</span>
    <span class="s-card__price">$412.50</span>
    <span class="s-card__caption">Sold 12 Mar 2025</span>
  </li>
</div>`;

describe("parseSoldListings", () => {
  it("parses the legacy result markup", () => {
    const listings = parseSoldListings(LEGACY_HTML);

    expect(listings).toHaveLength(2);
    expect(listings[0]).toMatchObject({
      price: 389.99,
      currency: "USD",
      shippingIncluded: true,
      detectedGrade: "PSA 10",
    });
    expect(listings[0].soldAt?.slice(0, 10)).toBe("2025-03-03");
  });

  it("strips eBay tracking parameters from item links", () => {
    const [first] = parseSoldListings(LEGACY_HTML);
    expect(first.url).toBe("https://www.ebay.com/itm/123456");
  });

  it("skips the 'Shop on eBay' promo row", () => {
    const listings = parseSoldListings(LEGACY_HTML);
    expect(listings.some((l) => l.title === "Shop on eBay")).toBe(false);
  });

  it("marks paid shipping as not included", () => {
    const listings = parseSoldListings(LEGACY_HTML);
    expect(listings[1].shippingIncluded).toBe(false);
  });

  it("parses the newer card markup too", () => {
    const listings = parseSoldListings(MODERN_HTML);

    expect(listings).toHaveLength(1);
    expect(listings[0].price).toBe(412.5);
    expect(listings[0].soldAt?.slice(0, 10)).toBe("2025-03-12");
  });

  it("returns nothing for a bot-check page rather than throwing", () => {
    expect(parseSoldListings("<html><body>Pardon our interruption</body></html>")).toEqual([]);
  });

  it("honours the limit", () => {
    expect(parseSoldListings(LEGACY_HTML, 1)).toHaveLength(1);
  });
});

describe("parseSoldDate", () => {
  it("strips the Sold label", () => {
    expect(parseSoldDate("Sold  Mar 3, 2025")?.slice(0, 10)).toBe("2025-03-03");
  });

  it("rejects text that is not a date", () => {
    expect(parseSoldDate("Best offer accepted")).toBeNull();
  });

  it("rejects dates in the future", () => {
    expect(parseSoldDate("Sold Jan 1, 2999")).toBeNull();
  });
});

describe("soldSearchUrl", () => {
  it("requests sold and completed listings", () => {
    const url = new URL(soldSearchUrl("Charizard ex 054/165"));
    expect(url.searchParams.get("LH_Sold")).toBe("1");
    expect(url.searchParams.get("LH_Complete")).toBe("1");
    expect(url.searchParams.get("_nkw")).toBe("Charizard ex 054/165");
  });
});

describe("isPlausibleMatch", () => {
  const card = { name: "Charizard ex" } as Card;

  const listing = (title: string) => ({
    title,
    price: 10,
    currency: "USD" as const,
    soldAt: null,
    url: null,
    imageUrl: null,
    shippingIncluded: false,
    detectedGrade: null,
  });

  it("accepts a listing naming the card", () => {
    expect(__testing.isPlausibleMatch(listing("Charizard ex 054/165 NM"), card)).toBe(true);
  });

  it("rejects a bulk lot", () => {
    expect(__testing.isPlausibleMatch(listing("Charizard ex lot of 5 cards"), card)).toBe(false);
  });

  it("rejects a 'you pick' listing", () => {
    expect(__testing.isPlausibleMatch(listing("Pokemon 151 you pick Charizard ex"), card)).toBe(
      false,
    );
  });

  it("rejects a listing for a different card", () => {
    expect(__testing.isPlausibleMatch(listing("Blastoise ex 009/165"), card)).toBe(false);
  });

  it("rejects custom proxies", () => {
    expect(__testing.isPlausibleMatch(listing("Charizard ex custom proxy card"), card)).toBe(false);
  });
});
