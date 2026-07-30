import type { Currency } from "./types";

const SYMBOL_TO_CURRENCY: Record<string, Currency> = {
  $: "USD",
  "€": "EUR",
  "£": "GBP",
};

/**
 * Rewrite a number's separators into plain `1234.56` form.
 *
 * Both conventions turn up — US sources write "1,250.00" and European ones
 * write "1.250,00" — and the same character means opposite things in each. Two
 * rules resolve it:
 *
 *   - When both separators appear, the rightmost is the decimal point and the
 *     other is grouping.
 *   - When only one appears, it is a decimal point if it is followed by one or
 *     two digits, and grouping otherwise. This is what separates "43,50" (forty
 *     three and a half) from "1,250" (one thousand two hundred and fifty).
 */
function normaliseSeparators(raw: string): string {
  const lastDot = raw.lastIndexOf(".");
  const lastComma = raw.lastIndexOf(",");

  if (lastDot === -1 && lastComma === -1) return raw;

  const decimalIndex = Math.max(lastDot, lastComma);
  const trailingDigits = raw.length - decimalIndex - 1;

  const bothPresent = lastDot !== -1 && lastComma !== -1;
  const isDecimal = bothPresent || (trailingDigits >= 1 && trailingDigits <= 2);

  if (!isDecimal) return raw.replace(/[.,]/g, "");

  const whole = raw.slice(0, decimalIndex).replace(/[.,]/g, "");
  return `${whole}.${raw.slice(decimalIndex + 1)}`;
}

/**
 * Pull a price out of free-form text such as "US $124.99", "£89.00" or
 * "EUR 43,50". Returns null when the text holds no recognisable amount.
 */
export function parseMoney(input: string): { amount: number; currency: Currency } | null {
  if (!input) return null;

  const text = input.replace(/\s+/g, " ").trim();

  let currency: Currency = "USD";
  const codeMatch = text.match(/\b(USD|EUR|GBP)\b/i);
  if (codeMatch) {
    currency = codeMatch[1].toUpperCase() as Currency;
  } else {
    for (const [symbol, code] of Object.entries(SYMBOL_TO_CURRENCY)) {
      if (text.includes(symbol)) {
        currency = code;
        break;
      }
    }
  }

  const numberMatch = text.match(/\d[\d.,\s]*\d|\d/);
  if (!numberMatch) return null;

  const amount = Number.parseFloat(normaliseSeparators(numberMatch[0].replace(/\s/g, "")));
  if (!Number.isFinite(amount)) return null;

  return { amount, currency };
}

export function formatMoney(amount: number | null | undefined, currency: Currency = "USD"): string {
  if (amount === null || amount === undefined || !Number.isFinite(amount)) return "—";
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

/**
 * Grades appear in eBay titles in a dozen shapes: "PSA 10", "psa10",
 * "CGC 9.5", "BGS 9 Mint". Normalise the ones we can recognise so sold
 * listings can be split by grade — a PSA 10 and a raw copy are different
 * markets and averaging them together produces a meaningless number.
 */
export function detectGrade(title: string): string | null {
  const match = title.match(/\b(PSA|BGS|CGC|SGC|ACE|TAG)\s*[-:]?\s*(10|[1-9](?:\.5)?)\b/i);
  if (match) {
    return `${match[1].toUpperCase()} ${match[2]}`;
  }
  if (/\b(raw|ungraded)\b/i.test(title)) return "Ungraded";
  return null;
}
